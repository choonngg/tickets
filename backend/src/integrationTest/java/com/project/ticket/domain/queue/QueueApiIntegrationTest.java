package com.project.ticket.domain.queue;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

import com.project.ticket.domain.concert.entity.Concert;
import com.project.ticket.domain.concert.repository.ConcertRepository;
import com.project.ticket.domain.queue.service.QueueAdmissionWorker;
import com.project.ticket.domain.seat.entity.Seat;
import com.project.ticket.domain.seat.repository.SeatRepository;
import com.project.ticket.domain.ticket.repository.TicketRepository;
import com.project.ticket.domain.user.entity.User;
import com.project.ticket.domain.user.repository.UserRepository;
import com.project.ticket.global.auth.JwtProvider;
import com.project.ticket.infra.queue.QueueRedisRepository;
import com.project.ticket.infra.queue.QueueScripts;
import com.project.ticket.support.IntegrationTestSupport;
import com.project.ticket.support.TestUsers;
import com.project.ticket.support.TestEntryTokens;
import io.jsonwebtoken.Jwts;
import java.math.BigInteger;
import java.security.KeyFactory;
import java.security.interfaces.RSAPublicKey;
import java.security.spec.RSAPublicKeySpec;
import java.time.Clock;
import java.time.Instant;
import java.util.Base64;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.http.MediaType;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.databind.json.JsonMapper;

@SpringBootTest(properties = {"ticket.queue.max-concurrent=2", "ticket.queue.admit-per-cycle=1"})
@AutoConfigureMockMvc
@Transactional
class QueueApiIntegrationTest extends IntegrationTestSupport {
    static final Instant NOW = Instant.parse("2030-01-01T00:00:00Z");
    @Autowired MockMvc mvc;
    @Autowired UserRepository users;
    @Autowired ConcertRepository concerts;
    @Autowired SeatRepository seats;
    @Autowired TicketRepository tickets;
    @Autowired QueueAdmissionWorker worker;
    @Autowired QueueRedisRepository queue;
    @Autowired StringRedisTemplate redis;
    @Autowired JwtProvider login;
    @Autowired JsonMapper mapper;
    @MockitoBean Clock clock;
    User fan;
    Concert concert;
    Concert broken;
    String bearer;
    String purchaseRedisKey;

    @BeforeEach void setup() {
        when(clock.instant()).thenReturn(NOW);
        fan = users.saveAndFlush(TestUsers.fan("Queue integration fan"));
        User artist = users.saveAndFlush(TestUsers.artist("Queue integration artist"));
        concert = concerts.saveAndFlush(Concert.create(artist, "Queue integration concert", "Venue",
                NOW.plusSeconds(7200), NOW.minusSeconds(1), NOW.plusSeconds(3600)));
        bearer = "Bearer " + login.createAccessToken(fan.getId(), fan.getRole(), 1L);
    }

    @AfterEach void clearQueue() {
        if (purchaseRedisKey != null) redis.delete(purchaseRedisKey);
        for (Concert item : broken == null ? List.of(concert) : List.of(concert, broken)) {
            redis.delete(QueueScripts.keys(item.getId()).subList(0, 8));
            redis.opsForSet().remove(QueueScripts.ACTIVE_PERFORMANCES, item.getId().toString());
        }
    }

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void missingAdmissionOnAdmittedOrActiveIssuesPurchasableTokenAndCannotReenterAfterConsumption(boolean active) throws Exception {
        Seat seat = seats.saveAndFlush(Seat.create(concert, "A", 1, 1, 10000));
        queue.register(concert.getId(), fan.getId());
        worker.runCycle();
        if (active) enter();
        Double originalScore = redis.opsForZSet().score(key(active ? "active" : "admitted"), fan.getId().toString());
        if (active) when(clock.instant()).thenReturn(NOW.plusSeconds(10));
        redis.opsForHash().delete(key("registered-at"), fan.getId() + ":admission");
        var first = enter();
        Object admission = redis.opsForHash().get(key("registered-at"), fan.getId() + ":admission");
        assertThat(admission).isNotNull();
        assertThat(UUID.fromString(admission.toString())).isNotNull();
        when(clock.instant()).thenReturn(NOW.plusSeconds(active ? 11 : 1));
        var retry = enter();
        assertThat(retry.get("entryToken").asText()).isEqualTo(first.get("entryToken").asText());
        assertThat(retry.get("expiresAt").asText()).isEqualTo(first.get("expiresAt").asText());
        assertThat(redis.opsForHash().get(key("registered-at"), fan.getId() + ":admission")).isEqualTo(admission);
        assertThat(redis.opsForZSet().score(key("admitted"), fan.getId().toString())).isNull();
        assertThat(redis.opsForZSet().score(key("active"), fan.getId().toString()))
                .isEqualTo(active ? originalScore : (double) NOW.plusSeconds(300).toEpochMilli());
        String requestKey = UUID.randomUUID().toString();
        purchaseRedisKey = "idempotency:tickets:v3:" + fan.getId() + ":" + requestKey;
        mvc.perform(post("/api/tickets").header("Authorization", bearer)
                        .header("X-Entry-Token", first.get("entryToken").asText()).header("Idempotency-Key", requestKey)
                        .contentType(MediaType.APPLICATION_JSON).content("{\"seatIds\":[" + seat.getId() + "]}"))
                .andExpect(status().isCreated()).andExpect(jsonPath("$[0].seatId").value(seat.getId()));
        assertThat(tickets.countBySeatId(seat.getId())).isEqualTo(1);
        mvc.perform(post("/api/queue/{id}/enter", concert.getId()).header("Authorization", bearer))
                .andExpect(status().isConflict()).andExpect(jsonPath("$.code").value("QUEUE_NOT_ADMITTED"));
        assertThat(redis.opsForZSet().score(key("active"), fan.getId().toString())).isNull();
        assertThat(redis.opsForHash().get(key("registered-at"), fan.getId() + ":admission")).isNull();
    }

    @Test void actualEntryTokenPurchasesAndRejectedTokensNeverCreateRedisIdempotencyKey() throws Exception {
        Seat seat = seats.saveAndFlush(Seat.create(concert, "A", 1, 1, 10000));
        mvc.perform(post("/api/queue/{id}", concert.getId()).header("Authorization", bearer))
                .andExpect(status().isOk());
        worker.runCycle();
        String entryToken = enter().get("entryToken").asText();
        String requestKey = UUID.randomUUID().toString();
        purchaseRedisKey = "idempotency:tickets:v3:" + fan.getId() + ":" + requestKey;
        String[] invalid = {null, "", "invalid", bearer.substring(7),
                TestEntryTokens.sign(TestEntryTokens.builder(concert.getId(), fan.getId(), NOW)),
                TestEntryTokens.issue(concert.getId(), fan.getId() + 1, clock),
                TestEntryTokens.issue(concert.getId() + 1, fan.getId(), clock)};
        String[] codes = {"ENTRY_TOKEN_REQUIRED", "ENTRY_TOKEN_REQUIRED", "ENTRY_TOKEN_INVALID",
                "ENTRY_TOKEN_INVALID", "ENTRY_TOKEN_EXPIRED", "ENTRY_TOKEN_MISMATCH", "ENTRY_TOKEN_MISMATCH"};
        for (int i = 0; i < invalid.length; i++) {
            var request = post("/api/tickets").header("Authorization", bearer)
                    .header("Idempotency-Key", requestKey).contentType(MediaType.APPLICATION_JSON)
                    .content("{\"seatIds\":[" + seat.getId() + "]}");
            if (invalid[i] != null) request.header("X-Entry-Token", invalid[i]);
            mvc.perform(request).andExpect(status().isForbidden()).andExpect(jsonPath("$.code").value(codes[i]));
            assertThat(redis.hasKey(purchaseRedisKey)).isFalse();
            assertThat(tickets.countBySeatId(seat.getId())).isZero();
        }
        mvc.perform(post("/api/tickets").header("Authorization", bearer)
                        .header("X-Entry-Token", entryToken).header("Idempotency-Key", requestKey)
                        .contentType(MediaType.APPLICATION_JSON).content("{\"seatIds\":[" + seat.getId() + "]}"))
                .andExpect(status().isCreated()).andExpect(jsonPath("$[0].seatId").value(seat.getId()));
        assertThat(tickets.countBySeatId(seat.getId())).isEqualTo(1);
        assertThat(redis.opsForValue().get(purchaseRedisKey)).contains("COMPLETED");
        when(clock.instant()).thenReturn(NOW.plusSeconds(300));
        mvc.perform(get("/api/tickets/me").header("Authorization", bearer))
                .andExpect(status().isOk()).andExpect(jsonPath("$[0].ticketId")
                        .value(tickets.findBySeatId(seat.getId()).orElseThrow().getId()));
    }

    @Test void registrationWorkerAdmissionEntryAndRetryUseRealRedisAndMysql() throws Exception {
        mvc.perform(post("/api/queue/{id}", concert.getId()).header("Authorization", bearer))
                .andExpect(status().isOk()).andExpect(jsonPath("$.status").value("WAITING"))
                .andExpect(jsonPath("$.rank").value(1)).andExpect(jsonPath("$.behind").value(0))
                .andExpect(jsonPath("$.serverTime").value(NOW.toString()))
                .andExpect(jsonPath("$.nextPollAfterMs").value(org.hamcrest.Matchers.allOf(
                        org.hamcrest.Matchers.greaterThanOrEqualTo(2400), org.hamcrest.Matchers.lessThanOrEqualTo(3600))));
        User second = users.saveAndFlush(TestUsers.fan("Second"));
        User third = users.saveAndFlush(TestUsers.fan("Third"));
        queue.register(concert.getId(), second.getId());
        queue.register(concert.getId(), third.getId());
        Object originalAdmission = redis.opsForHash().get(key("registered-at"), fan.getId() + ":admission");
        assertThat(originalAdmission).isNotNull();
        mvc.perform(get("/api/queue/{id}/status", concert.getId()).header("Authorization", bearer))
                .andExpect(status().isOk()).andExpect(jsonPath("$.behind").value(2))
                .andExpect(jsonPath("$.serverTime").value(NOW.toString()));
        mvc.perform(post("/api/queue/{id}/enter", concert.getId()).header("Authorization", bearer))
                .andExpect(status().isConflict()).andExpect(jsonPath("$.code").value("QUEUE_NOT_ADMITTED"));
        worker.runCycle();
        mvc.perform(get("/api/queue/{id}/status", concert.getId()).header("Authorization", bearer))
                .andExpect(status().isOk()).andExpect(jsonPath("$.status").value("ADMITTED"))
                .andExpect(jsonPath("$.expiresAt").value(NOW.plusSeconds(60).toString()));
        var first = enter();
        assertThat(redis.opsForHash().get(key("registered-at"), fan.getId() + ":admission")).isEqualTo(originalAdmission);
        when(clock.instant()).thenReturn(NOW.plusSeconds(1));
        var retry = enter();
        assertThat(redis.opsForHash().get(key("registered-at"), fan.getId() + ":admission")).isEqualTo(originalAdmission);
        assertThat(first.get("serverTime").asText()).isEqualTo(NOW.toString());
        assertThat(retry.get("serverTime").asText()).isEqualTo(NOW.plusSeconds(1).toString());
        assertThat(first.get("expiresAt").asText()).isEqualTo(retry.get("expiresAt").asText());
        var key = mapper.readTree(mvc.perform(get("/api/queue/.well-known/jwks.json")).andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString()).get("keys").get(0);
        RSAPublicKey publicKey = (RSAPublicKey) KeyFactory.getInstance("RSA").generatePublic(new RSAPublicKeySpec(
                new BigInteger(1, Base64.getUrlDecoder().decode(key.get("n").asText())),
                new BigInteger(1, Base64.getUrlDecoder().decode(key.get("e").asText()))));
        for (var response : List.of(first, retry)) {
            var signed = Jwts.parser().verifyWith(publicKey).clock(() -> java.util.Date.from(NOW))
                    .build().parseSignedClaims(response.get("entryToken").asText());
            assertThat(signed.getHeader().getKeyId()).isEqualTo("test-queue-key-1");
            assertThat(signed.getPayload().getIssuer()).isEqualTo("queue-service");
            assertThat(signed.getPayload().getAudience()).containsExactly("booking-service");
            assertThat(signed.getPayload().getSubject()).isEqualTo(fan.getId().toString());
            assertThat(signed.getPayload().get("pid", String.class)).isEqualTo(concert.getId().toString());
            assertThat(signed.getPayload().get("rid", String.class)).isEqualTo(originalAdmission);
            assertThat(signed.getPayload().getExpiration().toInstant()).isEqualTo(NOW.plusSeconds(300));
            mvc.perform(get("/api/users/me").header("Authorization", "Bearer " + response.get("entryToken").asText()))
                    .andExpect(status().isUnauthorized());
        }
        assertThat(queue.status(concert.getId(), fan.getId()).state()).isEqualTo(QueueRedisRepository.State.ACTIVE);
        for (int i = 0; i < 2; i++) worker.runCycle();
        assertThat(redis.opsForZSet().zCard(key("admitted")) + redis.opsForZSet().zCard(key("active"))).isEqualTo(2);
        assertThat(queue.status(concert.getId(), third.getId()).state()).isEqualTo(QueueRedisRepository.State.WAITING);
        assertThat(redis.opsForList().range(key("admit-history"), 0, -1)).containsExactly("0", "1", "1");
        assertThat(redis.opsForValue().get(key("admit-rate"))).isEqualTo("2");
        assertThat(queue.status(concert.getId(), third.getId()).admitRate()).isEqualTo(2);
        Double activeScore = redis.opsForZSet().score(key("active"), fan.getId().toString());
        mvc.perform(delete("/api/queue/{id}", concert.getId()).header("Authorization", bearer))
                .andExpect(status().isConflict()).andExpect(jsonPath("$.code").value("QUEUE_ALREADY_ACTIVE"))
                .andExpect(jsonPath("$.message").value("Queue user is already active. Cancellation is not allowed."));
        assertThat(redis.opsForZSet().score(key("active"), fan.getId().toString())).isEqualTo(activeScore);
        worker.runCycle();
        assertThat(redis.opsForZSet().zCard(key("admitted")) + redis.opsForZSet().zCard(key("active"))).isEqualTo(2);
        assertThat(queue.status(concert.getId(), third.getId()).state()).isEqualTo(QueueRedisRepository.State.WAITING);
        var afterCancel = enter();
        var claims = Jwts.parser().verifyWith(publicKey).clock(() -> java.util.Date.from(NOW.plusSeconds(1)))
                .build().parseSignedClaims(first.get("entryToken").asText()).getPayload();
        assertThat(claims.getExpiration().toInstant()).isEqualTo(NOW.plusSeconds(300));
        assertThat(afterCancel.get("expiresAt").asText()).isEqualTo(first.get("expiresAt").asText());
        mvc.perform(get("/api/queue/{id}/status", concert.getId()).header("Authorization", bearer))
                .andExpect(status().isOk()).andExpect(jsonPath("$.status").value("ACTIVE"));
    }

    @Test void waitingAdmittedAndUnregisteredCancellationRemainIdempotent() throws Exception {
        // An authenticated user with no registration can delete repeatedly.
        for (int i = 0; i < 2; i++) mvc.perform(delete("/api/queue/{id}", concert.getId())
                .header("Authorization", bearer)).andExpect(status().isNoContent());
        for (boolean admitted : new boolean[]{false, true}) {
            queue.register(concert.getId(), fan.getId());
            if (admitted) worker.runCycle();
            for (int i = 0; i < 2; i++) mvc.perform(delete("/api/queue/{id}", concert.getId())
                    .header("Authorization", bearer)).andExpect(status().isNoContent());
            assertThat(queue.status(concert.getId(), fan.getId()).state()).isEqualTo(QueueRedisRepository.State.EXPIRED);
        }
    }

    @Test void workerIsolatesActualScriptErrorAndUpdatesHealthyPerformanceRate() {
        broken = concerts.saveAndFlush(Concert.create(concert.getArtist(), "Broken queue", "Venue",
                NOW.plusSeconds(7200), NOW.minusSeconds(1), NOW.plusSeconds(3600)));
        queue.register(broken.getId(), fan.getId());
        queue.register(concert.getId(), fan.getId());
        redis.opsForValue().set("queue:" + broken.getId() + ":active", "Injected wrong Redis type");
        worker.runCycle();
        assertThat(queue.status(concert.getId(), fan.getId()).state()).isEqualTo(QueueRedisRepository.State.ADMITTED);
        assertThat(redis.opsForValue().get(key("admit-rate"))).isEqualTo("1");
        assertThat(redis.opsForZSet().zCard("queue:" + broken.getId() + ":waiting")).isEqualTo(1);
        assertThat(redis.opsForValue().get("queue:" + broken.getId() + ":admit-rate")).isNull();
    }

    private tools.jackson.databind.JsonNode enter() throws Exception {
        return mapper.readTree(mvc.perform(post("/api/queue/{id}/enter", concert.getId()).header("Authorization", bearer))
                .andExpect(status().isOk()).andExpect(jsonPath("$.status").value("ACTIVE"))
                .andReturn().getResponse().getContentAsString());
    }
    private String key(String suffix) { return "queue:" + concert.getId() + ":" + suffix; }
}
