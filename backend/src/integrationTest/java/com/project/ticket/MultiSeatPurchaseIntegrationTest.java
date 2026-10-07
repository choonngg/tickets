package com.project.ticket;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;
import com.project.ticket.domain.concert.entity.Concert;
import com.project.ticket.domain.concert.repository.ConcertRepository;
import com.project.ticket.domain.queue.service.EntryTokenProvider;
import com.project.ticket.domain.queue.service.QueueAdmissionWorker;
import com.project.ticket.domain.seat.entity.Seat;
import com.project.ticket.domain.seat.entity.SeatStatus;
import com.project.ticket.domain.seat.repository.SeatRepository;
import com.project.ticket.domain.ticket.dto.TicketPurchaseRequest;
import com.project.ticket.domain.ticket.repository.TicketRepository;
import com.project.ticket.domain.ticket.service.TicketService;
import com.project.ticket.domain.ticket.service.purchase.*;
import com.project.ticket.domain.user.repository.UserRepository;
import com.project.ticket.global.exception.BusinessException;
import com.project.ticket.global.exception.ErrorCode;
import com.project.ticket.infra.payment.PaymentClient;
import com.project.ticket.infra.payment.PaymentResult;
import com.project.ticket.infra.queue.QueueRedisRepository;
import com.project.ticket.support.IntegrationTestSupport;
import com.project.ticket.support.TestUsers;
import java.time.Clock;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.*;
import org.junit.jupiter.api.*;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.bean.override.mockito.MockitoBean;

@SpringBootTest(properties = {"ticket.queue.max-concurrent=1", "ticket.queue.admit-per-cycle=1",
        "ticket.queue.worker-initial-delay-ms=3600000"})
class MultiSeatPurchaseIntegrationTest extends IntegrationTestSupport {
    @Autowired UserRepository users;
    @Autowired ConcertRepository concerts;
    @Autowired SeatRepository seats;
    @Autowired TicketRepository tickets;
    @Autowired TicketService service;
    @Autowired TicketPurchaseStrategyRouter router;
    @Autowired TicketPurchaseProperties properties;
    @Autowired QueueRedisRepository queue;
    @Autowired QueueAdmissionWorker worker;
    @Autowired EntryTokenProvider tokens;
    @Autowired Clock clock;
    @Autowired org.springframework.jdbc.core.JdbcTemplate jdbc;
    @MockitoBean PaymentClient payment;
    long fan, other, concert;
    List<Long> ids;

    @BeforeEach void setup() {
        when(payment.pay(any(), any(), anyInt())).thenReturn(PaymentResult.success());
        fan = users.save(TestUsers.fan("Batch fan")).getId();
        other = users.save(TestUsers.fan("Batch other")).getId();
        var artist = users.save(TestUsers.artist("Batch artist"));
        var now = clock.instant();
        var show = concerts.save(Concert.create(artist, "Batch", "Venue", now.plusSeconds(7200),
                now.minusSeconds(60), now.plusSeconds(3600)));
        concert = show.getId();
        ids = java.util.stream.IntStream.range(0, 6).mapToObj(i ->
                seats.save(Seat.create(show, "A", 1, i + 1, 10000)).getId()).toList();
    }
    @AfterEach void reset() { properties.setPurchaseStrategy(PurchaseStrategy.DISTRIBUTED); }
    String enter(long user) {
        queue.register(concert, user);
        queue.admit(concert);
        var active = queue.enter(concert, user);
        assertThat(active.state()).isEqualTo(QueueRedisRepository.State.ACTIVE);
        return tokens.issue(concert, user, active.expiresAt(), active.admissionId());
    }
    ErrorCode attempt(long user, List<Long> selection, String admission) {
        try { router.current().purchase(user, selection, UUID.randomUUID().toString(), admission); return null; }
        catch (BusinessException e) { return e.errorCode(); }
    }

    @org.springframework.beans.factory.annotation.Value("${spring.datasource.url}") String jdbcUrl;

    // MySQL 8.0 has no Innodb_deadlocks status; INNODB_METRICS needs PROCESS, so read it as the container's root.
    long deadlocks() throws java.sql.SQLException {
        try (var connection = java.sql.DriverManager.getConnection(jdbcUrl, "root", "ticket");
             var rows = connection.createStatement().executeQuery(
                     "SELECT `COUNT` FROM information_schema.INNODB_METRICS WHERE NAME = 'lock_deadlocks'")) {
            rows.next();
            return rows.getLong(1);
        }
    }

    @ParameterizedTest @EnumSource(PurchaseStrategy.class)
    void overlappingBatchesLockInAscendingOrderWithoutDeadlock(PurchaseStrategy strategy) throws Exception {
        properties.setPurchaseStrategy(strategy);
        long deadlocksBefore = deadlocks();
        var start = new CountDownLatch(1);
        try (var executor = Executors.newFixedThreadPool(2)) {
            var a = executor.submit(() -> { start.await(); return attempt(fan, List.of(ids.get(1), ids.get(0)), "a"); });
            var b = executor.submit(() -> { start.await(); return attempt(other, List.of(ids.get(2), ids.get(1)), "b"); });
            start.countDown();
            ErrorCode first = a.get(30, TimeUnit.SECONDS), second = b.get(30, TimeUnit.SECONDS);
            assertThat((first == null ? 1 : 0) + (second == null ? 1 : 0)).isEqualTo(1);
            assertThat(first == null ? second : first).isIn(ErrorCode.SEAT_NOT_AVAILABLE, ErrorCode.SEAT_LOCK_FAILED);
            assertThat(tickets.countByUserIdAndSeatConcertId(fan, concert)
                    + tickets.countByUserIdAndSeatConcertId(other, concert)).isEqualTo(2);
            Long loserOnlySeat = first == null ? ids.get(2) : ids.get(0);
            assertThat(seats.findById(loserOnlySeat).orElseThrow().getStatus()).isEqualTo(SeatStatus.AVAILABLE);
            verify(payment, times(1)).pay(any(), any(), eq(20000));
            assertThat(deadlocks()).isEqualTo(deadlocksBefore);
        }
    }

    @ParameterizedTest @EnumSource(PurchaseStrategy.class)
    void userRowLockProtectsLimitEvenForDifferentAdmissionClaims(PurchaseStrategy strategy) throws Exception {
        properties.setPurchaseStrategy(strategy);
        assertThat(attempt(fan, ids.subList(0, 2), "initial")).isNull();
        var start = new CountDownLatch(1);
        try (var executor = Executors.newFixedThreadPool(2)) {
            var a = executor.submit(() -> { start.await(); return attempt(fan, ids.subList(2, 4), "second"); });
            var b = executor.submit(() -> { start.await(); return attempt(fan, ids.subList(4, 6), "third"); });
            start.countDown();
            var first = a.get(30, TimeUnit.SECONDS); var second = b.get(30, TimeUnit.SECONDS);
            assertThat((first == null ? 1 : 0) + (second == null ? 1 : 0)).isEqualTo(1);
            assertThat(first == null ? second : first).isEqualTo(ErrorCode.PURCHASE_LIMIT_EXCEEDED);
            assertThat(tickets.countByUserIdAndSeatConcertId(fan, concert)).isEqualTo(4);
        }
    }

    @Test void consumedTokenReleasesCapacityWorkerAdmitsNextAndReregistrationGoesToTail() {
        String token = enter(fan);
        queue.register(concert, other);
        var request = new TicketPurchaseRequest(ids.subList(0, 2));
        String key = UUID.randomUUID().toString();
        var first = service.purchase(fan, request, key, token);
        assertThat(queue.status(concert, fan).state()).isEqualTo(QueueRedisRepository.State.EXPIRED);
        assertThat(service.purchase(fan, new TicketPurchaseRequest(ids.subList(0, 2).reversed()), key, token)).isEqualTo(first);
        assertThatThrownBy(() -> service.purchase(fan, new TicketPurchaseRequest(ids.subList(2, 4)), "new", token))
                .isInstanceOfSatisfying(BusinessException.class, e -> assertThat(e.errorCode()).isEqualTo(ErrorCode.ENTRY_TOKEN_INVALID));
        long third = users.save(TestUsers.fan("Queue third")).getId();
        queue.register(concert, third);
        assertThat(queue.register(concert, fan).rank()).isEqualTo(3);
        worker.runCycle();
        assertThat(queue.status(concert, other).state()).isEqualTo(QueueRedisRepository.State.ADMITTED);
        assertThat(queue.status(concert, fan).rank()).isEqualTo(2);
    }

    @Test void sameTokenDifferentKeysOnlyOneRequestRunsAndPaymentFailureReleasesClaim() throws Exception {
        String token = enter(fan);
        var entered = new CountDownLatch(1); var finish = new CountDownLatch(1);
        when(payment.pay(any(), any(), anyInt())).thenAnswer(invocation -> {
            entered.countDown();
            assertThat(finish.await(10, TimeUnit.SECONDS)).isTrue();
            return PaymentResult.failure();
        });
        var request = new TicketPurchaseRequest(ids.subList(0, 2));
        try (var executor = Executors.newSingleThreadExecutor()) {
            var first = executor.submit(() -> assertThatThrownBy(() -> service.purchase(fan, request, "first", token))
                    .isInstanceOfSatisfying(BusinessException.class, e -> assertThat(e.errorCode()).isEqualTo(ErrorCode.PAYMENT_FAILED)));
            try {
                assertThat(entered.await(10, TimeUnit.SECONDS)).isTrue();
                assertThatThrownBy(() -> service.purchase(fan, request, "second", token))
                        .isInstanceOfSatisfying(BusinessException.class, e -> assertThat(e.errorCode()).isEqualTo(ErrorCode.REQUEST_IN_PROGRESS));
            } finally { finish.countDown(); }
            first.get(10, TimeUnit.SECONDS);
        }
        assertThat(queue.status(concert, fan).state()).isEqualTo(QueueRedisRepository.State.ACTIVE);
        when(payment.pay(any(), any(), anyInt())).thenReturn(PaymentResult.success());
        assertThat(service.purchase(fan, request, "second", token)).hasSize(2);
    }
}
