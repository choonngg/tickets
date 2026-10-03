package com.project.ticket;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;

import com.jayway.jsonpath.JsonPath;
import com.project.ticket.domain.concert.entity.Concert;
import com.project.ticket.domain.concert.repository.ConcertRepository;
import com.project.ticket.domain.seat.entity.Seat;
import com.project.ticket.domain.seat.entity.SeatStatus;
import com.project.ticket.domain.seat.repository.SeatRepository;
import com.project.ticket.domain.ticket.repository.TicketRepository;
import com.project.ticket.domain.ticket.service.purchase.PurchaseStrategy;
import com.project.ticket.domain.ticket.service.purchase.TicketPurchaseProperties;
import com.project.ticket.domain.user.entity.User;
import com.project.ticket.domain.user.repository.UserRepository;
import com.project.ticket.global.auth.JwtProvider;
import com.project.ticket.support.IntegrationTestSupport;
import java.time.Clock;
import java.time.LocalDateTime;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.stream.IntStream;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;

@AutoConfigureMockMvc
class MultiUserTicketPurchaseIntegrationTest extends IntegrationTestSupport {
    private static final int USER_COUNT = 50;

    @Autowired MockMvc mvc;
    @Autowired JwtProvider jwt;
    @Autowired Clock clock;
    @Autowired TicketPurchaseProperties properties;
    @Autowired UserRepository users;
    @Autowired ConcertRepository concerts;
    @Autowired SeatRepository seats;
    @Autowired TicketRepository tickets;
    @Autowired StringRedisTemplate redis;

    @AfterEach
    void resetStrategy() {
        properties.setPurchaseStrategy(PurchaseStrategy.DISTRIBUTED);
    }

    @ParameterizedTest
    @EnumSource(PurchaseStrategy.class)
    void fiftyDifferentUsersBuyingSameSeatCreateExactlyOneTicket(PurchaseStrategy strategy) throws Exception {
        properties.setPurchaseStrategy(strategy);
        User artist = users.save(User.createArtist("Concurrency Artist"));
        LocalDateTime now = LocalDateTime.now(clock);
        Concert concert = concerts.save(Concert.create(artist, "Concurrency Concert", "Venue",
                now.plusDays(30), now.minusDays(1), now.plusDays(20)));
        Seat seat = seats.save(Seat.create(concert, "A", 1, 1, 10_000));
        var fans = IntStream.range(0, USER_COUNT)
                .mapToObj(index -> users.save(User.createFan("Fan " + index))).toList();
        var tokens = fans.stream().map(fan -> jwt.createAccessToken(fan.getId(), fan.getRole())).toList();
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
                        .header("Idempotency-Key", keys.get(index))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"seatId\":" + seat.getId() + "}"))
                        .andReturn().getResponse();
            })).toList();
            assertThat(ready.await(30, TimeUnit.SECONDS)).isTrue();
            start.countDown();

            int successes = 0;
            for (var result : results) {
                var response = result.get(60, TimeUnit.SECONDS);
                if (response.getStatus() == 201) {
                    successes++;
                    Number responseSeatId = JsonPath.read(response.getContentAsString(), "$.seatId");
                    assertThat(responseSeatId.longValue()).isEqualTo(seat.getId());
                } else {
                    assertThat(response.getStatus()).isEqualTo(409);
                    String code = JsonPath.read(response.getContentAsString(), "$.code");
                    // Current strategies map lock acquisition, optimistic and unique conflicts to SEAT_LOCK_FAILED.
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
                    .mapToObj(index -> "idempotency:tickets:" + fans.get(index).getId() + ":" + keys.get(index))
                    .toList());
        }
    }
}
