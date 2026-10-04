package com.project.ticket.domain.ticket.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;

import com.project.ticket.domain.ticket.dto.TicketPurchaseRequest;
import com.project.ticket.domain.ticket.dto.TicketPurchaseResponse;
import com.project.ticket.domain.ticket.repository.TicketRepository;
import com.project.ticket.domain.ticket.service.purchase.TicketPurchaseProperties;
import com.project.ticket.domain.ticket.service.purchase.TicketPurchaseStrategy;
import com.project.ticket.domain.ticket.service.purchase.TicketPurchaseStrategyRouter;
import com.project.ticket.global.exception.BusinessException;
import com.project.ticket.global.exception.ErrorCode;
import com.project.ticket.infra.idempotency.IdempotencyService;
import com.project.ticket.support.InMemoryIdempotencyStore;
import java.time.Clock;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;

class TicketIdempotencyTest {
    @Test
    void concurrentDuplicateStopsBeforePurchaseAndDifferentBodyReturns422() throws Exception {
        CountDownLatch entered = new CountDownLatch(1);
        CountDownLatch finish = new CountDownLatch(1);
        AtomicInteger purchases = new AtomicInteger();
        TicketService service = service((user, seat) -> {
            assertThat(purchases.incrementAndGet()).isEqualTo(1);
            entered.countDown();
            try {
                assertThat(finish.await(5, TimeUnit.SECONDS)).isTrue();
            } catch (InterruptedException exception) {
                throw new IllegalStateException(exception);
            }
            return response(user, seat);
        });
        try (var executor = Executors.newSingleThreadExecutor()) {
            var first = executor.submit(() -> service.purchase(1L, new TicketPurchaseRequest(2L), "key"));
            try {
                assertThat(entered.await(5, TimeUnit.SECONDS)).isTrue();
                assertError(() -> service.purchase(1L, new TicketPurchaseRequest(2L), "key"),
                        ErrorCode.REQUEST_IN_PROGRESS, 409);
                assertError(() -> service.purchase(1L, new TicketPurchaseRequest(3L), "key"),
                        ErrorCode.IDEMPOTENCY_CONFLICT, 422);
            } finally {
                finish.countDown();
            }
            var completed = first.get(5, TimeUnit.SECONDS);
            assertThat(service.purchase(1L, new TicketPurchaseRequest(2L), "key")).isEqualTo(completed);
            assertThat(purchases).hasValue(1);
        }
    }

    @Test
    void completedRequestRejectsDifferentSeatAndScopesKeyToUser() {
        AtomicInteger purchases = new AtomicInteger();
        TicketService service = service((user, seat) -> {
            purchases.incrementAndGet();
            return response(user, seat);
        });
        var first = service.purchase(1L, new TicketPurchaseRequest(2L), "key");
        assertThat(service.purchase(1L, new TicketPurchaseRequest(2L), "key")).isEqualTo(first);
        assertError(() -> service.purchase(1L, new TicketPurchaseRequest(3L), "key"),
                ErrorCode.IDEMPOTENCY_CONFLICT, 422);
        assertThat(service.purchase(4L, new TicketPurchaseRequest(3L), "key").userId()).isEqualTo(4L);
        assertThat(purchases).hasValue(2);
    }

    @Test
    void failedPurchaseReleasesMarkerForRetry() {
        AtomicInteger purchases = new AtomicInteger();
        TicketService service = service((user, seat) -> {
            if (purchases.incrementAndGet() == 1) throw new BusinessException(ErrorCode.PAYMENT_FAILED);
            return response(user, seat);
        });
        assertError(() -> service.purchase(1L, new TicketPurchaseRequest(2L), "key"),
                ErrorCode.PAYMENT_FAILED, 400);
        assertThat(service.purchase(1L, new TicketPurchaseRequest(2L), "key").seatId()).isEqualTo(2L);
        assertThat(purchases).hasValue(2);
    }

    private TicketService service(TicketPurchaseStrategy strategy) {
        var router = new TicketPurchaseStrategyRouter(new TicketPurchaseProperties(), strategy, strategy, strategy);
        return new TicketService(new IdempotencyService(new InMemoryIdempotencyStore(Clock.systemUTC())),
                router, mock(TicketRepository.class));
    }

    private TicketPurchaseResponse response(Long user, Long seat) {
        return new TicketPurchaseResponse(10L, user, seat, 20);
    }

    private void assertError(Runnable purchase, ErrorCode code, int status) {
        assertThatThrownBy(purchase::run).isInstanceOfSatisfying(BusinessException.class, exception -> {
            assertThat(exception.errorCode()).isEqualTo(code);
            assertThat(exception.errorCode().status().value()).isEqualTo(status);
        });
    }
}
