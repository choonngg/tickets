package com.project.ticket;

import com.project.ticket.support.TestUsers;
import com.project.ticket.support.TestEntryTokens;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;

import com.jayway.jsonpath.JsonPath;
import com.project.ticket.domain.concert.entity.Concert;
import com.project.ticket.domain.concert.repository.ConcertRepository;
import com.project.ticket.domain.seat.entity.Seat;
import com.project.ticket.domain.seat.entity.SeatStatus;
import com.project.ticket.domain.seat.repository.SeatRepository;
import com.project.ticket.domain.ticket.entity.Ticket;
import com.project.ticket.domain.ticket.repository.TicketRepository;
import com.project.ticket.domain.ticket.service.purchase.PurchaseStrategy;
import com.project.ticket.domain.ticket.service.purchase.TicketPurchaseProperties;
import com.project.ticket.domain.ticket.service.purchase.TicketPurchaseStrategyRouter;
import com.project.ticket.domain.user.entity.User;
import com.project.ticket.domain.user.repository.UserRepository;
import com.project.ticket.global.auth.JwtProvider;
import com.project.ticket.global.exception.BusinessException;
import com.project.ticket.global.exception.ErrorCode;
import com.project.ticket.support.IntegrationTestSupport;
import jakarta.persistence.EntityManagerFactory;
import java.time.Clock;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.stream.IntStream;
import org.hibernate.SessionFactory;
import org.hibernate.resource.jdbc.spi.StatementInspector;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.http.MediaType;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.web.servlet.MockMvc;

@AutoConfigureMockMvc
@TestPropertySource(properties = "spring.jpa.properties.hibernate.session_factory.statement_inspector="
        + "com.project.ticket.MultiUserTicketPurchaseIntegrationTest$SqlRecorder")
class MultiUserTicketPurchaseIntegrationTest extends IntegrationTestSupport {
    private static final int USER_COUNT = 50;

    @Autowired com.project.ticket.infra.queue.QueueRedisRepository queue;
    @Autowired MockMvc mvc;
    @Autowired JwtProvider jwt;
    @Autowired Clock clock;
    @Autowired TicketPurchaseProperties properties;
    @Autowired TicketPurchaseStrategyRouter router;
    @Autowired EntityManagerFactory entityManagerFactory;
    @Autowired UserRepository users;
    @Autowired ConcertRepository concerts;
    @Autowired SeatRepository seats;
    @Autowired TicketRepository tickets;
    @Autowired StringRedisTemplate redis;

    @AfterEach
    void resetStrategy() {
        properties.setPurchaseStrategy(PurchaseStrategy.DISTRIBUTED);
    }

    @Test
    void optimisticSeatUpdateExecutesBeforeTicketInsert() {
        properties.setPurchaseStrategy(PurchaseStrategy.OPTIMISTIC);
        Seat seat = createSeat();
        Seat second = seats.save(Seat.create(seat.getConcert(), "A", 1, 2, 20000));
        User fan = users.save(TestUsers.fan("SQL Fan"));
        SqlRecorder recorder = (SqlRecorder) entityManagerFactory.unwrap(SessionFactory.class)
                .getSessionFactoryOptions().getStatementInspector();
        recorder.statements.clear();

        router.current().purchase(fan.getId(), java.util.List.of(second.getId(), seat.getId()), "purchase", "admission");

        assertThat(recorder.statements).hasSize(4);
        assertThat(recorder.statements.subList(0, 2)).allSatisfy(sql ->
                assertThat(sql).startsWith("update seat ").contains("version=?"));
        assertThat(recorder.statements.subList(2, 4)).allSatisfy(sql ->
                assertThat(sql).startsWith("insert into ticket "));
        assertThat(tickets.countBySeatId(seat.getId())).isEqualTo(1);
    }

    @ParameterizedTest
    @EnumSource(PurchaseStrategy.class)
    void ticketSeatUniqueViolationReturnsSeatNotAvailableAndRollsBackSeat(PurchaseStrategy strategy) {
        properties.setPurchaseStrategy(strategy);
        Seat seat = createSeat();
        User owner = users.save(TestUsers.fan("Existing owner"));
        tickets.save(Ticket.complete(owner, seat, seat.getPrice(), clock.instant()));
        User other = users.save(TestUsers.fan("Other buyer"));

        // Simulate a broken upper layer: an existing ticket with an AVAILABLE seat.
        assertThatThrownBy(() -> router.current().purchase(other.getId(), java.util.List.of(seat.getId()), "purchase", "admission"))
                .isInstanceOfSatisfying(BusinessException.class,
                        exception -> assertThat(exception.errorCode()).isEqualTo(ErrorCode.SEAT_NOT_AVAILABLE));

        assertThat(tickets.countBySeatId(seat.getId())).isEqualTo(1);
        Seat unchanged = seats.findById(seat.getId()).orElseThrow();
        assertThat(unchanged.getStatus()).isEqualTo(SeatStatus.AVAILABLE);
        assertThat(unchanged.getVersion()).isEqualTo(seat.getVersion());
    }

    @ParameterizedTest
    @EnumSource(PurchaseStrategy.class)
    void fiftyDifferentUsersBuyingSameSeatCreateExactlyOneTicket(PurchaseStrategy strategy) throws Exception {
        properties.setPurchaseStrategy(strategy);
        Seat seat = createSeat();
        var fans = IntStream.range(0, USER_COUNT)
                .mapToObj(index -> users.save(TestUsers.fan("Fan " + index))).toList();
        var tokens = fans.stream().map(fan -> jwt.createAccessToken(fan.getId(), fan.getRole(), 1L)).toList();
        var keys = IntStream.range(0, USER_COUNT).mapToObj(index -> UUID.randomUUID().toString()).toList();
        CountDownLatch ready = new CountDownLatch(USER_COUNT);
        CountDownLatch start = new CountDownLatch(1);
        var executor = Executors.newFixedThreadPool(USER_COUNT);
        try {
            var results = IntStream.range(0, USER_COUNT).mapToObj(index -> executor.submit(() -> {
                ready.countDown();
                assertThat(start.await(30, TimeUnit.SECONDS)).isTrue();
                return mvc.perform(post("/api/tickets")
                        .header("Authorization", "Bearer " + tokens.get(index))
                        .header("X-Entry-Token", TestEntryTokens.issue(queue, seat.getConcert().getId(), fans.get(index).getId(), clock))
                .header("Idempotency-Key", keys.get(index))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"seatIds\":[" + seat.getId() + "]}"))
                        .andReturn().getResponse();
            })).toList();
            assertThat(ready.await(30, TimeUnit.SECONDS)).isTrue();
            start.countDown();

            int successes = 0;
            for (var result : results) {
                var response = result.get(60, TimeUnit.SECONDS);
                if (response.getStatus() == 201) {
                    successes++;
                    Number responseSeatId = JsonPath.read(response.getContentAsString(), "$[0].seatId");
                    assertThat(responseSeatId.longValue()).isEqualTo(seat.getId());
                } else {
                    assertThat(response.getStatus()).isEqualTo(409);
                    String code = JsonPath.read(response.getContentAsString(), "$.code");
                    // Unavailable seats and lock/version contention have separate conflict codes.
                    assertThat(code).isIn("SEAT_NOT_AVAILABLE", "SEAT_LOCK_FAILED");
                }
            }

            assertThat(successes).isEqualTo(1);
            assertThat(tickets.countBySeatId(seat.getId())).isEqualTo(1);
            assertThat(seats.findById(seat.getId()).orElseThrow().getStatus()).isEqualTo(SeatStatus.SOLD);
        } finally {
            start.countDown();
            executor.shutdownNow();
            assertThat(executor.awaitTermination(30, TimeUnit.SECONDS)).isTrue();
            redis.delete(IntStream.range(0, USER_COUNT)
                    .mapToObj(index -> "idempotency:tickets:v3:" + fans.get(index).getId() + ":" + keys.get(index))
                    .toList());
        }
    }

    private Seat createSeat() {
        User artist = users.save(TestUsers.artist("Concurrency Artist"));
        Instant now = clock.instant();
        Concert concert = concerts.save(Concert.create(artist, "Concurrency Concert", "Venue",
                now.plus(30, ChronoUnit.DAYS), now.minus(1, ChronoUnit.DAYS), now.plus(20, ChronoUnit.DAYS)));
        return seats.save(Seat.create(concert, "A", 1, 1, 10_000));
    }

    public static class SqlRecorder implements StatementInspector {
        final List<String> statements = Collections.synchronizedList(new ArrayList<>());

        @Override
        public String inspect(String sql) {
            if (sql.startsWith("update seat ") || sql.startsWith("insert into ticket ")) {
                statements.add(sql);
            }
            return sql;
        }
    }
}
