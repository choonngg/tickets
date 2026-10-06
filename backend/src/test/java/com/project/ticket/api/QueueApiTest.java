package com.project.ticket.api;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

import com.project.ticket.domain.concert.entity.Concert;
import com.project.ticket.domain.concert.repository.ConcertRepository;
import com.project.ticket.domain.queue.service.EntryTokenProvider;
import com.project.ticket.domain.user.entity.User;
import com.project.ticket.domain.user.repository.UserRepository;
import com.project.ticket.global.auth.JwtProvider;
import com.project.ticket.global.exception.BusinessException;
import com.project.ticket.global.exception.ErrorCode;
import com.project.ticket.infra.queue.QueueRedisRepository;
import com.project.ticket.infra.queue.QueueRedisRepository.State;
import com.project.ticket.support.TestUsers;
import io.jsonwebtoken.Jwts;
import java.math.BigInteger;
import java.security.KeyFactory;
import java.security.interfaces.RSAPublicKey;
import java.security.spec.RSAPublicKeySpec;
import java.time.Clock;
import java.time.Instant;
import java.util.Base64;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.databind.json.JsonMapper;

@ActiveProfiles("test")
@SpringBootTest
@AutoConfigureMockMvc
@Transactional
class QueueApiTest {
    @Autowired MockMvc mvc;
    @Autowired UserRepository users;
    @Autowired ConcertRepository concerts;
    @Autowired JwtProvider login;
    @Autowired EntryTokenProvider entry;
    @MockitoBean Clock clock;
    @Autowired JsonMapper mapper;
    @MockitoBean QueueRedisRepository queue;
    User fan;
    Concert concert;
    Instant now;
    String bearer;

    @BeforeEach void setup() {
        now = Instant.parse("2030-01-01T00:00:00Z");
        when(clock.instant()).thenReturn(now);
        fan = users.save(TestUsers.fan("Queue fan"));
        User artist = users.save(TestUsers.artist("Queue artist"));
        concert = concerts.save(Concert.create(artist, "Queue concert", "Venue", now.plusSeconds(7200),
                now.minusSeconds(60), now.plusSeconds(3600)));
        bearer = "Bearer " + login.createAccessToken(fan.getId(), fan.getRole(), 1L);
    }

    @Test void registrationRequiresSaleAndActiveUser() throws Exception {
        for (Instant open : new Instant[]{now.plusSeconds(100), now.minusSeconds(200)}) {
            Concert unavailable = concerts.save(Concert.create(concert.getArtist(), "Unavailable", "Venue",
                    now.plusSeconds(1000), open, open.plusSeconds(50)));
            mvc.perform(post("/api/queue/{id}", unavailable.getId()).header("Authorization", bearer))
                    .andExpect(status().isConflict()).andExpect(jsonPath("$.code").value("CONCERT_NOT_ON_SALE"));
        }
        fan.delete(now);
        for (var request : java.util.List.of(post("/api/queue/{id}", concert.getId()),
                post("/api/queue/{id}/enter", concert.getId()),
                delete("/api/queue/{id}", concert.getId()))) {
            mvc.perform(request.header("Authorization", bearer)).andExpect(status().isNotFound())
                    .andExpect(jsonPath("$.code").value("USER_NOT_FOUND"));
        }
        verifyNoInteractions(queue);
        when(queue.status(concert.getId(), fan.getId())).thenReturn(result(State.EXPIRED, 0, 0, 0, 0));
        mvc.perform(get("/api/queue/{id}/status", concert.getId()).header("Authorization", bearer))
                .andExpect(status().isOk()).andExpect(jsonPath("$.status").value("EXPIRED"))
                .andExpect(jsonPath("$.serverTime").value(now.toString()));
    }

    @Test void missingConcertStatusIsExpired() throws Exception {
        when(queue.status(Long.MAX_VALUE, fan.getId())).thenReturn(result(State.EXPIRED, 0, 0, 0, 0));
        mvc.perform(get("/api/queue/{id}/status", Long.MAX_VALUE).header("Authorization", bearer))
                .andExpect(status().isOk()).andExpect(jsonPath("$.status").value("EXPIRED"))
                .andExpect(jsonPath("$.serverTime").value(now.toString()));
        verify(queue).status(Long.MAX_VALUE, fan.getId());
    }

    @Test void registerAndStatusExposeRankBehindJitterAndMeasuredEstimate() throws Exception {
        when(queue.register(concert.getId(), fan.getId())).thenReturn(result(State.WAITING, 1, 2, 2700, 0));
        mvc.perform(post("/api/queue/{id}", concert.getId()).header("Authorization", bearer))
                .andExpect(status().isOk()).andExpect(jsonPath("$.status").value("WAITING"))
                .andExpect(jsonPath("$.rank").value(1)).andExpect(jsonPath("$.behind").value(2))
                .andExpect(jsonPath("$.estimatedWaitSeconds").isEmpty())
                .andExpect(jsonPath("$.serverTime").value(now.toString()));
        when(queue.status(concert.getId(), fan.getId())).thenReturn(result(State.WAITING, 3, 0, 3300, 120));
        mvc.perform(get("/api/queue/{id}/status", concert.getId()).header("Authorization", bearer))
                .andExpect(status().isOk()).andExpect(jsonPath("$.rank").value(3))
                .andExpect(jsonPath("$.behind").value(0)).andExpect(jsonPath("$.nextPollAfterMs").value(3300))
                .andExpect(jsonPath("$.estimatedWaitSeconds").value(1.5))
                .andExpect(jsonPath("$.expiresAt").value(now.plusSeconds(9).toString()))
                .andExpect(jsonPath("$.serverTime").value(now.toString()));
        verify(queue).status(concert.getId(), fan.getId());
    }

    @Test void entryRequiresAdmissionAndExpiredAdmissionIsRejected() throws Exception {
        for (State state : new State[]{State.NOT_ADMITTED, State.EXPIRED}) {
            when(queue.enter(concert.getId(), fan.getId())).thenReturn(result(state, 0, 0, 0, 0));
            mvc.perform(post("/api/queue/{id}/enter", concert.getId()).header("Authorization", bearer))
                    .andExpect(status().isConflict()).andExpect(jsonPath("$.code").value(
                            state == State.EXPIRED ? "QUEUE_ADMISSION_EXPIRED" : "QUEUE_NOT_ADMITTED"));
        }
    }

    @Test void entryAndActiveRetryIssueQueueClaimsWithoutExtendingExpiry() throws Exception {
        Instant expiry = now.plusSeconds(300);
        when(queue.enter(concert.getId(), fan.getId())).thenReturn(
                new QueueRedisRepository.Status(State.ACTIVE, 0, 0, expiry, 0, 0, 2));
        RSAPublicKey publicKey = queuePublicKey();
        for (int i = 0; i < 2; i++) {
            String token = mapper.readTree(mvc.perform(post("/api/queue/{id}/enter", concert.getId())
                            .header("Authorization", bearer)).andExpect(status().isOk())
                    .andExpect(jsonPath("$.status").value("ACTIVE"))
                    .andExpect(jsonPath("$.expiresAt").value(expiry.toString()))
                    .andExpect(jsonPath("$.serverTime").value(now.toString()))
                    .andReturn().getResponse().getContentAsString()).get("entryToken").asText();
            var jwt = Jwts.parser().verifyWith(publicKey).build().parseSignedClaims(token);
            assertThat(jwt.getHeader().getAlgorithm()).isEqualTo("RS256");
            assertThat(jwt.getHeader().getType()).isEqualTo("JWT");
            assertThat(jwt.getHeader().getKeyId()).isEqualTo("test-queue-key-1");
            assertThat(jwt.getPayload().getIssuer()).isEqualTo("queue-service");
            assertThat(jwt.getPayload().getAudience()).containsExactly("booking-service");
            assertThat(jwt.getPayload().getSubject()).isEqualTo(fan.getId().toString());
            assertThat(jwt.getPayload().get("pid", String.class)).isEqualTo(concert.getId().toString());
            assertThat(jwt.getPayload().getExpiration().toInstant().getEpochSecond()).isEqualTo(expiry.getEpochSecond());
            mvc.perform(get("/api/queue/{id}/status", concert.getId()).header("Authorization", "Bearer " + token))
                    .andExpect(status().isUnauthorized()).andExpect(jsonPath("$.code").value("UNAUTHORIZED"));
        }
        verify(queue, times(2)).enter(concert.getId(), fan.getId());
    }

    @Test void activeCancellationReturnsConflictUsingStandardErrorBody() throws Exception {
        doThrow(new BusinessException(ErrorCode.QUEUE_ALREADY_ACTIVE)).when(queue).cancel(concert.getId(), fan.getId());
        mvc.perform(delete("/api/queue/{id}", concert.getId()).header("Authorization", bearer))
                .andExpect(status().isConflict()).andExpect(jsonPath("$.code").value("QUEUE_ALREADY_ACTIVE"))
                .andExpect(jsonPath("$.message").value("Queue user is already active. Cancellation is not allowed."));
        verify(queue).cancel(concert.getId(), fan.getId());
        verifyNoMoreInteractions(queue);
    }

    @Test void cancelIsIdempotentAndFollowingStatusIsExpired() throws Exception {
        for (int i = 0; i < 2; i++) mvc.perform(delete("/api/queue/{id}", concert.getId()).header("Authorization", bearer))
                .andExpect(status().isNoContent());
        when(queue.status(concert.getId(), fan.getId())).thenReturn(result(State.EXPIRED, 0, 0, 0, 0));
        mvc.perform(get("/api/queue/{id}/status", concert.getId()).header("Authorization", bearer))
                .andExpect(status().isOk()).andExpect(jsonPath("$.status").value("EXPIRED"))
                .andExpect(jsonPath("$.expiresAt").isEmpty()).andExpect(jsonPath("$.nextPollAfterMs").value(0));
        verify(queue, times(2)).cancel(concert.getId(), fan.getId());
    }

    @Test void everyQueueOperationRequiresAuthentication() throws Exception {
        for (var request : java.util.List.of(post("/api/queue/1"), get("/api/queue/1/status"),
                post("/api/queue/1/enter"), delete("/api/queue/1"))) {
            mvc.perform(request).andExpect(status().isUnauthorized()).andExpect(jsonPath("$.code").value("UNAUTHORIZED"));
        }
        verifyNoInteractions(queue);
    }

    @Test void queueJwksIsPublicAndSeparateFromLoginJwks() throws Exception {
        var key = mapper.readTree(mvc.perform(get("/api/queue/.well-known/jwks.json"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.keys.length()").value(1))
                .andExpect(jsonPath("$.keys[0].kid").value("test-queue-key-1"))
                .andReturn().getResponse().getContentAsString()).get("keys").get(0);
        assertThat(key.propertyNames()).containsExactlyInAnyOrder("kid", "kty", "alg", "use", "n", "e");
        var loginKey = mapper.readTree(mvc.perform(get("/.well-known/jwks.json"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.keys.length()").value(1))
                .andExpect(jsonPath("$.keys[0].kid").value("test-key-1"))
                .andReturn().getResponse().getContentAsString()).get("keys").get(0);
        assertThat(key.get("n").asText()).isNotEqualTo(loginKey.get("n").asText());
    }

    private QueueRedisRepository.Status result(State state, long rank, long behind, long poll, long rate) {
        return new QueueRedisRepository.Status(state, rank, behind, state == State.WAITING ? now.plusSeconds(9) : null,
                state == State.WAITING ? 3000 : 0, poll, rate);
    }

    private RSAPublicKey queuePublicKey() throws Exception {
        var key = mapper.valueToTree(entry.jwks()).get("keys").get(0);
        return (RSAPublicKey) KeyFactory.getInstance("RSA").generatePublic(new RSAPublicKeySpec(
                new BigInteger(1, Base64.getUrlDecoder().decode(key.get("n").asText())),
                new BigInteger(1, Base64.getUrlDecoder().decode(key.get("e").asText()))));
    }
}
