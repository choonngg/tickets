package com.project.ticket.domain.ticket.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;
import static org.mockito.ArgumentMatchers.anyLong;

import com.project.ticket.domain.ticket.service.EntryTokenValidator;
import com.project.ticket.domain.seat.repository.SeatRepository;
import com.project.ticket.domain.seat.entity.Seat;
import com.project.ticket.domain.concert.entity.Concert;
import com.project.ticket.support.TestEntryTokens;
import java.util.Optional;

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
        TicketService service = service((user, seatIds, purchaseKey, admissionId) -> {
            assertThat(purchases.incrementAndGet()).isEqualTo(1);
            entered.countDown();
            try {
                assertThat(finish.await(5, TimeUnit.SECONDS)).isTrue();
            } catch (InterruptedException exception) {
                throw new IllegalStateException(exception);
            }
            return java.util.List.of(response(user, seatIds.getFirst()));
        });
        try (var executor = Executors.newSingleThreadExecutor()) {
            var first = executor.submit(() -> service.purchase(1L, new TicketPurchaseRequest(java.util.List.of(2L)), "key", TestEntryTokens.issue(10L, 1L, Clock.systemUTC())));
            try {
                assertThat(entered.await(5, TimeUnit.SECONDS)).isTrue();
                assertError(() -> service.purchase(1L, new TicketPurchaseRequest(java.util.List.of(2L)), "key", TestEntryTokens.issue(10L, 1L, Clock.systemUTC())),
                        ErrorCode.REQUEST_IN_PROGRESS, 409);
                assertError(() -> service.purchase(1L, new TicketPurchaseRequest(java.util.List.of(3L)), "key", TestEntryTokens.issue(10L, 1L, Clock.systemUTC())),
                        ErrorCode.IDEMPOTENCY_CONFLICT, 422);
            } finally {
                finish.countDown();
            }
            var completed = first.get(5, TimeUnit.SECONDS);
            assertThat(service.purchase(1L, new TicketPurchaseRequest(java.util.List.of(2L)), "key", TestEntryTokens.issue(10L, 1L, Clock.systemUTC()))).isEqualTo(completed);
            assertThat(purchases).hasValue(1);
        }
    }

    @Test
    void completedRequestRejectsDifferentSeatAndScopesKeyToUser() {
        AtomicInteger purchases = new AtomicInteger();
        TicketService service = service((user, seatIds, purchaseKey, admissionId) -> {
            purchases.incrementAndGet();
            return java.util.List.of(response(user, seatIds.getFirst()));
        });
        var first = service.purchase(1L, new TicketPurchaseRequest(java.util.List.of(2L)), "key", TestEntryTokens.issue(10L, 1L, Clock.systemUTC()));
        assertThat(service.purchase(1L, new TicketPurchaseRequest(java.util.List.of(2L)), "key", TestEntryTokens.issue(10L, 1L, Clock.systemUTC()))).isEqualTo(first);
        assertError(() -> service.purchase(1L, new TicketPurchaseRequest(java.util.List.of(3L)), "key", TestEntryTokens.issue(10L, 1L, Clock.systemUTC())),
                ErrorCode.IDEMPOTENCY_CONFLICT, 422);
        assertThat(service.purchase(4L, new TicketPurchaseRequest(java.util.List.of(3L)), "key", TestEntryTokens.issue(10L, 4L, Clock.systemUTC())).getFirst().userId()).isEqualTo(4L);
        assertThat(purchases).hasValue(2);
    }

    @Test
    void failedPurchaseReleasesMarkerForRetry() {
        AtomicInteger purchases = new AtomicInteger();
        TicketService service = service((user, seatIds, purchaseKey, admissionId) -> {
            if (purchases.incrementAndGet() == 1) throw new BusinessException(ErrorCode.PAYMENT_FAILED);
            return java.util.List.of(response(user, seatIds.getFirst()));
        });
        assertError(() -> service.purchase(1L, new TicketPurchaseRequest(java.util.List.of(2L)), "key", TestEntryTokens.issue(10L, 1L, Clock.systemUTC())),
                ErrorCode.PAYMENT_FAILED, 400);
        assertThat(service.purchase(1L, new TicketPurchaseRequest(java.util.List.of(2L)), "key", TestEntryTokens.issue(10L, 1L, Clock.systemUTC())).getFirst().seatId()).isEqualTo(2L);
        assertThat(purchases).hasValue(2);
    }

    @Test
    void sameAdmissionAcrossDifferentKeysAllowsOnlyOnePurchaseAndFailureReleasesIt() throws Exception {
        var entered = new CountDownLatch(1);
        var finish = new CountDownLatch(1);
        var attempts = new AtomicInteger();
        TicketService service = service((user, ids, purchaseKey, admissionId) -> {
            attempts.incrementAndGet();
            entered.countDown();
            try { finish.await(5, TimeUnit.SECONDS); }
            catch (InterruptedException e) { throw new IllegalStateException(e); }
            throw new BusinessException(ErrorCode.PAYMENT_FAILED);
        });
        String token = TestEntryTokens.issue(10, 777, Clock.systemUTC());
        var request = new TicketPurchaseRequest(java.util.List.of(2L));
        try (var executor = Executors.newSingleThreadExecutor()) {
            var first = executor.submit(() -> {
                assertError(() -> service.purchase(777L, request, "one", token), ErrorCode.PAYMENT_FAILED, 400);
            });
            try {
                assertThat(entered.await(5, TimeUnit.SECONDS)).isTrue();
                assertError(() -> service.purchase(777L, request, "two", token), ErrorCode.REQUEST_IN_PROGRESS, 409);
            } finally { finish.countDown(); }
            first.get(5, TimeUnit.SECONDS);
            assertError(() -> service.purchase(777L, request, "two", token), ErrorCode.PAYMENT_FAILED, 400);
            assertThat(attempts).hasValue(2);
        }
    }

    @Test
    void claimFailureReleasesAdmissionWithSameOwner() {
        var store = mock(com.project.ticket.infra.queue.EntryAdmissionStore.class);
        org.mockito.Mockito.doThrow(new IllegalStateException("redis timeout")).when(store)
                .claim(anyLong(), anyLong(), org.mockito.ArgumentMatchers.anyString(),
                        org.mockito.ArgumentMatchers.any(), org.mockito.ArgumentMatchers.anyString());
        TicketService service = service((user, ids, purchaseKey, admissionId) -> {
            throw new AssertionError("purchase must not run");
        }, store);
        assertThatThrownBy(() -> service.purchase(1L, new TicketPurchaseRequest(java.util.List.of(2L)), "key",
                TestEntryTokens.issue(10L, 1L, Clock.systemUTC()))).isInstanceOf(IllegalStateException.class);
        var owner = org.mockito.ArgumentCaptor.forClass(String.class);
        var claimOwner = org.mockito.ArgumentCaptor.forClass(String.class);
        org.mockito.Mockito.verify(store).claim(anyLong(), anyLong(), org.mockito.ArgumentMatchers.anyString(),
                org.mockito.ArgumentMatchers.any(), claimOwner.capture());
        org.mockito.Mockito.verify(store).release(anyLong(), anyLong(), org.mockito.ArgumentMatchers.anyString(),
                owner.capture());
        assertThat(owner.getValue()).isEqualTo(claimOwner.getValue());
    }

    private TicketService service(TicketPurchaseStrategy strategy) {
        return service(strategy, new com.project.ticket.support.InMemoryEntryAdmissionStore(Clock.systemUTC()));
    }

    private TicketService service(TicketPurchaseStrategy strategy, com.project.ticket.infra.queue.EntryAdmissionStore store) {
        var router = new TicketPurchaseStrategyRouter(new TicketPurchaseProperties(), strategy, strategy, strategy);
        var seats = mock(SeatRepository.class);
        var seat = mock(Seat.class);
        var concert = mock(Concert.class);
        when(concert.getId()).thenReturn(10L);
        when(seat.getConcert()).thenReturn(concert);
        when(seats.findById(anyLong())).thenReturn(Optional.of(seat));
        var validator = new EntryTokenValidator(TestEntryTokens.PUBLIC_PEM, TestEntryTokens.KEY_ID,
                Clock.systemUTC(), seats);
        return new TicketService(new IdempotencyService(new InMemoryIdempotencyStore(Clock.systemUTC())),
                router, mock(TicketRepository.class), validator, store);
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
