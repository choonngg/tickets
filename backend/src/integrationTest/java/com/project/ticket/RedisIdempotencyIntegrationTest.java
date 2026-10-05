package com.project.ticket;

import com.project.ticket.support.TestUsers;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.Mockito.doAnswer;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.project.ticket.domain.concert.entity.Concert;
import com.project.ticket.domain.concert.repository.ConcertRepository;
import com.project.ticket.domain.seat.entity.Seat;
import com.project.ticket.domain.seat.entity.SeatStatus;
import com.project.ticket.domain.seat.repository.SeatRepository;
import com.project.ticket.domain.ticket.dto.TicketPurchaseResponse;
import com.project.ticket.domain.ticket.repository.TicketRepository;
import com.project.ticket.domain.user.entity.User;
import com.project.ticket.domain.user.repository.UserRepository;
import com.project.ticket.global.auth.JwtProvider;
import com.project.ticket.infra.idempotency.IdempotencyStore;
import com.project.ticket.infra.idempotency.RedisIdempotencyStore;
import com.project.ticket.infra.payment.MockPaymentClient;
import com.project.ticket.support.IntegrationTestSupport;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.http.MediaType;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;

@AutoConfigureMockMvc
class RedisIdempotencyIntegrationTest extends IntegrationTestSupport {
    @Autowired RedisIdempotencyStore store;
    @Autowired StringRedisTemplate redis;
    @Autowired MockMvc mvc;
    @Autowired JwtProvider jwt;
    @Autowired Clock clock;
    @Autowired UserRepository users;
    @Autowired ConcertRepository concerts;
    @Autowired SeatRepository seats;
    @Autowired TicketRepository tickets;
    @MockitoSpyBean MockPaymentClient payment;

    @Test
    void concurrentSameKeyReturns201And409ThenReplaysSame201() throws Exception {
        User fan = users.save(TestUsers.fan("Idempotency Fan"));
        User artist = users.save(TestUsers.artist("Idempotency Artist"));
        Instant now = clock.instant();
        Concert concert = concerts.save(Concert.create(artist, "Idempotency Concert", "Venue",
                now.plus(30, ChronoUnit.DAYS), now.minus(1, ChronoUnit.DAYS), now.plus(20, ChronoUnit.DAYS)));
        Seat firstSeat = seats.save(Seat.create(concert, "A", 1, 1, 10_000));
        Seat otherSeat = seats.save(Seat.create(concert, "A", 1, 2, 10_000));
        String key = UUID.randomUUID().toString();
        String redisKey = "idempotency:tickets:v2:" + fan.getId() + ":" + key;
        CountDownLatch enteredPayment = new CountDownLatch(1);
        CountDownLatch finishPayment = new CountDownLatch(1);
        doAnswer(invocation -> {
            enteredPayment.countDown();
            assertThat(finishPayment.await(30, TimeUnit.SECONDS)).isTrue();
            return invocation.callRealMethod();
        }).when(payment).pay(any(), any(), anyInt());

        try (var executor = Executors.newSingleThreadExecutor()) {
            var first = executor.submit(() -> purchase(fan, firstSeat, key)
                    .andExpect(status().isCreated()).andReturn().getResponse().getContentAsString());
            try {
                assertThat(enteredPayment.await(30, TimeUnit.SECONDS)).isTrue();
                purchase(fan, firstSeat, key).andExpect(status().isConflict())
                        .andExpect(jsonPath("$.code").value("REQUEST_IN_PROGRESS"));
                purchase(fan, otherSeat, key).andExpect(status().is(422))
                        .andExpect(jsonPath("$.code").value("IDEMPOTENCY_CONFLICT"));
            } finally {
                finishPayment.countDown();
            }
            String response = first.get(30, TimeUnit.SECONDS);
            assertThat(purchase(fan, firstSeat, key).andExpect(status().isCreated())
                    .andReturn().getResponse().getContentAsString()).isEqualTo(response);
            purchase(fan, otherSeat, key).andExpect(status().is(422))
                    .andExpect(jsonPath("$.code").value("IDEMPOTENCY_CONFLICT"));
            assertThat(tickets.countBySeatId(firstSeat.getId())).isEqualTo(1);
            assertThat(tickets.countBySeatId(otherSeat.getId())).isZero();
            assertThat(seats.findById(otherSeat.getId()).orElseThrow().getStatus())
                    .isEqualTo(SeatStatus.AVAILABLE);
        } finally {
            redis.delete(redisKey);
        }
    }

    @Test
    void luaRejectsForeignAndExpiredOwnersAndPreservesCompletedResponse() {
        String key = "integration:idempotency:" + UUID.randomUUID();
        // IDs beyond Lua cjson's exact integer range must round-trip unchanged.
        var response = new TicketPurchaseResponse(9_007_199_254_740_993L, 2L, 3L, 4);
        try {
            assertThat(store.tryAcquire(key, "hash", "old")).isEmpty();
            assertThat(redis.getExpire(key)).isBetween(1L, IdempotencyStore.IN_PROGRESS_TTL.toSeconds());
            assertThat(store.tryAcquire(key, "other-hash", "other")).get()
                    .isEqualTo(new IdempotencyStore.Entry("IN_PROGRESS", "hash", "old", null));
            assertThat(store.complete(key, "hash", "other", response)).isFalse();
            assertThat(store.releaseIfOwner(key, "other")).isFalse();
            assertThat(redis.expire(key, Duration.ZERO)).isTrue();
            assertThat(store.tryAcquire(key, "hash", "new")).isEmpty();
            assertThat(store.complete(key, "hash", "old", response)).isFalse();
            assertThat(store.releaseIfOwner(key, "old")).isFalse();
            assertThat(store.complete(key, "wrong-hash", "new", response)).isFalse();
            assertThat(store.complete(key, "hash", "new", response)).isTrue();
            assertThat(redis.getExpire(key)).isBetween(86_390L, IdempotencyStore.COMPLETED_TTL.toSeconds());
            assertThat(store.releaseIfOwner(key, "new")).isFalse();
            assertThat(store.complete(key, "hash", "new", response)).isFalse();
            assertThat(store.tryAcquire(key, "hash", "next")).get()
                    .isEqualTo(new IdempotencyStore.Entry("COMPLETED", "hash", "new", response));
        } finally {
            redis.delete(key);
        }
    }

    @Test
    void luaReleasesOnlyOwnedMarkerAndDoesNotRecreateMissingKey() {
        String key = "integration:idempotency:" + UUID.randomUUID();
        var response = new TicketPurchaseResponse(1L, 2L, 3L, 4);
        try {
            assertThat(store.complete(key, "hash", "owner", response)).isFalse();
            assertThat(store.releaseIfOwner(key, "owner")).isFalse();
            assertThat(redis.hasKey(key)).isFalse();
            assertThat(store.tryAcquire(key, "hash", "owner")).isEmpty();
            assertThat(store.releaseIfOwner(key, "owner")).isTrue();
            assertThat(store.tryAcquire(key, "hash", "next")).isEmpty();
        } finally {
            redis.delete(key);
        }
    }

    private ResultActions purchase(User fan, Seat seat, String key) throws Exception {
        return mvc.perform(post("/api/tickets")
                .header("Authorization", "Bearer " + jwt.createAccessToken(fan.getId(), fan.getRole(), 1L))
                .header("Idempotency-Key", key)
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"seatId\":" + seat.getId() + "}"));
    }
}
