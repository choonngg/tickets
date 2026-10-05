package com.project.ticket.domain.auth.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

import com.project.ticket.domain.auth.entity.RefreshToken;
import com.project.ticket.domain.auth.repository.RefreshTokenRepository;
import com.project.ticket.infra.google.GoogleOAuthClient.UserInfo;
import jakarta.servlet.http.Cookie;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;
import org.springframework.test.web.servlet.MockMvc;

// Deliberately no test transaction: observe committed state after the API returns.
@SpringBootTest
@ActiveProfiles("test")
@AutoConfigureMockMvc
class AuthSessionCommitTest {
    static final Instant NOW = Instant.parse("2026-10-05T03:00:00Z");
    @Autowired AuthService auth;
    @Autowired MockMvc mvc;
    @Autowired JdbcTemplate jdbc;
    @MockitoBean Clock clock;
    @MockitoSpyBean RefreshTokenRepository tokens;
    AuthService.IssuedTokens login;
    long sid;
    long userId;

    @BeforeEach
    void setup() {
        when(clock.instant()).thenReturn(NOW);
        when(clock.getZone()).thenReturn(ZoneOffset.UTC);
        login = auth.authenticateGoogle(new UserInfo(UUID.randomUUID().toString(), "commit@example.com", true, "Fan"));
        sid = jdbc.queryForObject("SELECT session_id FROM refresh_token WHERE token_hash = ?", Long.class, AuthService.hash(login.refreshToken()));
        userId = jdbc.queryForObject("SELECT user_id FROM auth_session WHERE id = ?", Long.class, sid);
    }

    @AfterEach
    void cleanup() {
        jdbc.update("DELETE FROM auth_session WHERE id = ?", sid);
        jdbc.update("DELETE FROM users WHERE id = ?", userId);
    }

    @Test
    void reuseRevocationRemainsCommittedAfter401() throws Exception {
        mvc.perform(post("/api/auth/refresh").cookie(new Cookie("refresh_token", login.refreshToken())))
                .andExpect(status().isOk());
        when(clock.instant()).thenReturn(NOW.plusSeconds(16));
        mvc.perform(post("/api/auth/refresh").cookie(new Cookie("refresh_token", login.refreshToken())))
                .andExpect(status().isUnauthorized()).andExpect(jsonPath("$.code").value("REAUTH_REQUIRED"));
        assertThat(jdbc.queryForObject("SELECT revoked_at FROM auth_session WHERE id = ?", Instant.class, sid))
                .isEqualTo(NOW.plusSeconds(16));
    }

    @Test
    void logoutPersistenceFailureReturnsErrorAndKeepsSession() throws Exception {
        doThrow(new IllegalStateException("simulated persistence failure")).when(tokens).findSessionIdByHash(any(byte[].class));
        mvc.perform(post("/api/auth/logout").cookie(new Cookie("refresh_token", login.refreshToken())))
                .andExpect(status().isInternalServerError()).andExpect(jsonPath("$.code").value("INTERNAL_SERVER_ERROR"))
                .andExpect(header().doesNotExist("Set-Cookie"));
        assertThat(jdbc.queryForObject("SELECT revoked_at FROM auth_session WHERE id = ?", Instant.class, sid)).isNull();
    }

    @Test
    void failedNewTokenInsertRollsBackOldTokenConsumption() throws Exception {
        doThrow(new IllegalStateException("simulated persistence failure")).when(tokens).save(any(RefreshToken.class));
        mvc.perform(post("/api/auth/refresh").cookie(new Cookie("refresh_token", login.refreshToken())))
                .andExpect(status().isInternalServerError()).andExpect(jsonPath("$.code").value("INTERNAL_SERVER_ERROR"));
        assertThat(jdbc.queryForObject("SELECT consumed_at FROM refresh_token WHERE token_hash = ?", Instant.class, AuthService.hash(login.refreshToken())))
                .isNull();
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM refresh_token WHERE session_id = ?", Long.class, sid)).isEqualTo(1);
    }
}
