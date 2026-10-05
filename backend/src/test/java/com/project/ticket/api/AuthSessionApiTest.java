package com.project.ticket.api;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

import com.project.ticket.domain.auth.service.AuthService;
import com.project.ticket.domain.concert.entity.Concert;
import com.project.ticket.domain.concert.repository.ConcertRepository;
import com.project.ticket.domain.seat.entity.Seat;
import com.project.ticket.domain.seat.repository.SeatRepository;
import com.project.ticket.domain.user.repository.UserRepository;
import com.project.ticket.global.auth.JwtProvider;
import com.project.ticket.infra.google.GoogleOAuthClient;
import com.project.ticket.support.TestUsers;
import jakarta.servlet.http.Cookie;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.transaction.annotation.Transactional;

@ActiveProfiles("test")
@SpringBootTest
@AutoConfigureMockMvc
@Transactional
class AuthSessionApiTest {
    static final Instant NOW = Instant.parse("2026-10-05T03:00:00Z");
    @Autowired MockMvc mvc;
    @Autowired JdbcTemplate jdbc;
    @Autowired jakarta.persistence.EntityManager em;
    @Autowired UserRepository users;
    @Autowired ConcertRepository concerts;
    @Autowired SeatRepository seats;
    @Autowired JwtProvider jwt;
    @MockitoBean GoogleOAuthClient google;
    @MockitoBean Clock clock;

    @BeforeEach
    void setup() {
        when(clock.instant()).thenReturn(NOW);
        when(clock.getZone()).thenReturn(ZoneOffset.UTC);
        String sub = UUID.randomUUID().toString();
        when(google.exchange("code")).thenReturn(new GoogleOAuthClient.UserInfo(sub, "fan@example.com", true, "Fan"));
    }

    @Test
    void refreshRotatesCookieAndReturnsOnlyAccessTokenWithSameSession() throws Exception {
        Cookie old = login();
        long sid = sessionId(old);
        when(clock.instant()).thenReturn(NOW.plusSeconds(60));
        var response = mvc.perform(post("/api/auth/refresh").cookie(old))
                .andExpect(status().isOk()).andExpect(jsonPath("$.tokenType").value("Bearer"))
                .andExpect(jsonPath("$.refreshToken").doesNotExist())
                .andExpect(header().string("Cache-Control", "no-store")).andReturn().getResponse();
        Cookie next = response.getCookie("refresh_token");
        assertThat(next.getValue()).isNotEqualTo(old.getValue());
        assertThat(next.getMaxAge()).isEqualTo(14 * 86400 - 60);
        assertThat(sessionId(next)).isEqualTo(sid);
        var body = new com.fasterxml.jackson.databind.ObjectMapper().readTree(response.getContentAsString());
        var claims = io.jsonwebtoken.Jwts.parser().verifyWith(com.project.ticket.support.TestJwtKeys.PUBLIC_KEY)
                .clock(() -> java.util.Date.from(NOW.plusSeconds(60))).build()
                .parseSignedClaims(body.get("accessToken").asText()).getPayload();
        assertThat(claims.get("sid", String.class)).isEqualTo(Long.toString(sid));
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM refresh_token WHERE session_id = ? AND consumed_at IS NULL", Long.class, sid)).isEqualTo(1);
    }

    @Test
    void refreshCookieMaxAgeFollowsRemainingSessionLifetime() throws Exception {
        Cookie old = login();
        assertThat(old.getMaxAge()).isEqualTo(14 * 86400);
        when(clock.instant()).thenReturn(NOW.plusSeconds(13 * 86400));
        var next = mvc.perform(post("/api/auth/refresh").cookie(old)).andExpect(status().isOk())
                .andReturn().getResponse().getCookie("refresh_token");
        assertThat(next.getMaxAge()).isEqualTo(86400);
    }

    @Test
    void consumedTokenAtFifteenSecondsRetriesEvenWhenLatestTokenWasConsumed() throws Exception {
        Cookie old = login();
        Cookie next = mvc.perform(post("/api/auth/refresh").cookie(old)).andExpect(status().isOk())
                .andReturn().getResponse().getCookie("refresh_token");
        mvc.perform(post("/api/auth/refresh").cookie(next)).andExpect(status().isOk());
        when(clock.instant()).thenReturn(NOW.plusSeconds(15));
        mvc.perform(post("/api/auth/refresh").cookie(old)).andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value("REFRESH_RETRY"))
                .andExpect(header().doesNotExist("Set-Cookie"));
        em.flush();
        assertThat(jdbc.queryForObject("SELECT revoked_at FROM auth_session WHERE id = ?", Instant.class, sessionId(old))).isNull();
    }

    @Test
    void staleReuseRevokesWholeFamilyAndDeletesCookie() throws Exception {
        Cookie old = login();
        Cookie next = mvc.perform(post("/api/auth/refresh").cookie(old)).andExpect(status().isOk())
                .andReturn().getResponse().getCookie("refresh_token");
        when(clock.instant()).thenReturn(NOW.plusSeconds(15).plusNanos(1000));
        mvc.perform(post("/api/auth/refresh").cookie(old)).andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value("REAUTH_REQUIRED"))
                .andExpect(cookie().maxAge("refresh_token", 0));
        em.flush();
        assertThat(jdbc.queryForObject("SELECT revoked_at FROM auth_session WHERE id = ?", Instant.class, sessionId(old)))
                .isEqualTo(NOW.plusSeconds(15).plusNanos(1000));
        mvc.perform(post("/api/auth/refresh").cookie(next)).andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value("REAUTH_REQUIRED"));
    }

    @Test
    void missingUnknownAndMalformedCookiesCannotRefreshOrLogout() throws Exception {
        for (String path : new String[]{"/api/auth/refresh", "/api/auth/logout"}) {
            mvc.perform(post(path)).andExpect(status().isUnauthorized())
                    .andExpect(jsonPath("$.code").value("REAUTH_REQUIRED"))
                    .andExpect(cookie().maxAge("refresh_token", 0));
            for (String value : new String[]{"", "garbage", "x".repeat(43), "x".repeat(1000)}) {
                mvc.perform(post(path).cookie(new Cookie("refresh_token", value))).andExpect(status().isUnauthorized())
                        .andExpect(jsonPath("$.code").value("REAUTH_REQUIRED"));
            }
        }
    }

    @Test
    void expiredSessionTakesPriorityOverReuseGrace() throws Exception {
        Cookie old = login();
        when(clock.instant()).thenReturn(NOW.plusSeconds(14 * 86400 - 1));
        mvc.perform(post("/api/auth/refresh").cookie(old)).andExpect(status().isOk());
        when(clock.instant()).thenReturn(NOW.plusSeconds(14 * 86400));
        mvc.perform(post("/api/auth/refresh").cookie(old)).andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value("REAUTH_REQUIRED"))
                .andExpect(cookie().maxAge("refresh_token", 0));
    }

    @Test
    void expiredBearerDoesNotBlockRefreshOrLogout() throws Exception {
        Cookie old = login();
        mvc.perform(post("/api/auth/refresh").header("Authorization", "Bearer expired").cookie(old))
                .andExpect(status().isOk());
        mvc.perform(post("/api/auth/logout").header("Authorization", "Bearer expired").cookie(old))
                .andExpect(status().isNoContent()).andExpect(cookie().maxAge("refresh_token", 0));
        mvc.perform(post("/api/auth/refresh").cookie(old)).andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value("REAUTH_REQUIRED"));
    }

    @Test
    void logoutRevokesOnlyCurrentSessionEvenWithConsumedToken() throws Exception {
        Cookie first = login();
        Cookie second = login();
        mvc.perform(post("/api/auth/refresh").cookie(first)).andExpect(status().isOk());
        mvc.perform(post("/api/auth/logout").cookie(first)).andExpect(status().isNoContent())
                .andExpect(cookie().path("refresh_token", "/api/auth"))
                .andExpect(cookie().maxAge("refresh_token", 0));
        mvc.perform(post("/api/auth/refresh").cookie(first)).andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value("REAUTH_REQUIRED"));
        mvc.perform(post("/api/auth/refresh").cookie(second)).andExpect(status().isOk());
    }

    @Test
    void withdrawalRevokesAllSessionsRejectsPurchaseAndAllowsGoogleRejoin() throws Exception {
        Cookie first = login();
        Cookie second = login();
        long sid = sessionId(first);
        long userId = jdbc.queryForObject("SELECT user_id FROM auth_session WHERE id = ?", Long.class, sid);
        String at = jwt.createAccessToken(userId, com.project.ticket.domain.user.entity.UserRole.FAN, sid);
        mvc.perform(delete("/api/users/me").header("Authorization", "Bearer " + at))
                .andExpect(status().isNoContent()).andExpect(cookie().maxAge("refresh_token", 0));
        em.flush();
        assertThat(jdbc.queryForObject("SELECT deleted_at FROM users WHERE id = ?", Instant.class, userId)).isEqualTo(NOW);
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM auth_session WHERE user_id = ? AND revoked_at IS NULL", Long.class, userId)).isZero();
        for (Cookie cookie : new Cookie[]{first, second}) {
            mvc.perform(post("/api/auth/refresh").cookie(cookie)).andExpect(status().isUnauthorized())
                    .andExpect(jsonPath("$.code").value("REAUTH_REQUIRED"));
        }
        var artist = users.save(TestUsers.artist("Artist"));
        var concert = concerts.save(Concert.create(artist, "Concert", "Venue", NOW.plusSeconds(86400), NOW.minusSeconds(1), NOW.plusSeconds(3600)));
        var seat = seats.save(Seat.create(concert, "A", 1, 1, 10000));
        mvc.perform(post("/api/tickets").header("Authorization", "Bearer " + at)
                        .header("Idempotency-Key", UUID.randomUUID().toString()).contentType(MediaType.APPLICATION_JSON)
                        .content("{\"seatId\":" + seat.getId() + "}"))
                .andExpect(status().isNotFound()).andExpect(jsonPath("$.code").value("USER_NOT_FOUND"));
        Cookie rejoined = login();
        mvc.perform(post("/api/auth/refresh").cookie(rejoined)).andExpect(status().isOk());
        em.flush();
        assertThat(jdbc.queryForObject("SELECT deleted_at FROM users WHERE id = ?", Instant.class, userId)).isNull();
        mvc.perform(post("/api/auth/refresh").cookie(first)).andExpect(status().isUnauthorized());
    }

    private Cookie login() throws Exception {
        return mvc.perform(get("/api/auth/google/callback").param("state", "state").param("code", "code")
                        .cookie(new Cookie("oauth_state", "state")))
                .andExpect(status().isFound()).andReturn().getResponse().getCookie("refresh_token");
    }

    private long sessionId(Cookie token) {
        return jdbc.queryForObject("SELECT session_id FROM refresh_token WHERE token_hash = ?", Long.class, AuthService.hash(token.getValue()));
    }
}
