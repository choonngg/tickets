package com.project.ticket.domain.queue;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

import com.project.ticket.domain.concert.entity.Concert;
import com.project.ticket.domain.concert.repository.ConcertRepository;
import com.project.ticket.domain.queue.service.QueueAdmissionWorker;
import com.project.ticket.domain.user.entity.User;
import com.project.ticket.domain.user.repository.UserRepository;
import com.project.ticket.global.auth.JwtProvider;
import com.project.ticket.infra.queue.QueueRedisRepository;
import com.project.ticket.infra.queue.QueueScripts;
import com.project.ticket.support.IntegrationTestSupport;
import com.project.ticket.support.TestUsers;
import io.jsonwebtoken.Jwts;
import java.math.BigInteger;
import java.security.KeyFactory;
import java.security.interfaces.RSAPublicKey;
import java.security.spec.RSAPublicKeySpec;
import java.time.Clock;
import java.time.Instant;
import java.util.Base64;
import java.util.List;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.data.redis.core.StringRedisTemplate;
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

    @BeforeEach void setup() {
        when(clock.instant()).thenReturn(NOW);
        fan = users.saveAndFlush(TestUsers.fan("Queue integration fan"));
        User artist = users.saveAndFlush(TestUsers.artist("Queue integration artist"));
        concert = concerts.saveAndFlush(Concert.create(artist, "Queue integration concert", "Venue",
                NOW.plusSeconds(7200), NOW.minusSeconds(1), NOW.plusSeconds(3600)));
        bearer = "Bearer " + login.createAccessToken(fan.getId(), fan.getRole(), 1L);
    }

    @AfterEach void clearQueue() {
        for (Concert item : broken == null ? List.of(concert) : List.of(concert, broken)) {
            redis.delete(QueueScripts.keys(item.getId()).subList(0, 8));
            redis.opsForSet().remove(QueueScripts.ACTIVE_PERFORMANCES, item.getId().toString());
        }
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
        when(clock.instant()).thenReturn(NOW.plusSeconds(1));
        var retry = enter();
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
