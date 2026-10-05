package com.project.ticket.api;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

import com.project.ticket.infra.google.GoogleOAuthClient;
import com.project.ticket.infra.google.GoogleOAuthClient.UserInfo;
import jakarta.servlet.http.Cookie;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Base64;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.transaction.annotation.Transactional;

@ActiveProfiles("test")
@SpringBootTest
@AutoConfigureMockMvc
@Transactional
class GoogleAuthApiTest {
    private static final Instant NOW = Instant.parse("2026-10-04T03:00:00Z");
    @Autowired MockMvc mvc;
    @Autowired JdbcTemplate jdbc;
    @Autowired jakarta.persistence.EntityManager entityManager;
    @MockitoBean GoogleOAuthClient google;
    @MockitoBean Clock clock;

    @BeforeEach
    void setup() {
        when(clock.instant()).thenReturn(NOW);
        when(clock.getZone()).thenReturn(ZoneOffset.UTC);
        when(google.exchange("valid-code")).thenReturn(new UserInfo("google-123", "fan@example.com", true, "Fan"));
    }

    @Test
    void loginRedirectSetsShortLivedSecureStateCookie() throws Exception {
        var response = mvc.perform(get("/api/auth/google/login")).andExpect(status().isFound())
                .andReturn().getResponse();
        Cookie state = response.getCookie("oauth_state");
        assertThat(Base64.getUrlDecoder().decode(state.getValue())).hasSize(32);
        assertThat(state.isHttpOnly()).isTrue();
        assertThat(state.getSecure()).isTrue();
        assertThat(state.getPath()).isEqualTo("/api/auth/google");
        assertThat(state.getMaxAge()).isEqualTo(600);
        assertThat(response.getHeader("Set-Cookie")).contains("SameSite=Lax").doesNotContain("Domain=");
        assertThat(response.getRedirectedUrl()).startsWith("https://accounts.google.com/o/oauth2/v2/auth?")
                .contains("state=" + state.getValue(), "response_type=code", "scope=openid%20email%20profile");
        verifyNoInteractions(google);
    }

    @Test
    void mismatchedOrMissingStateAndMissingCodeAreRejectedBeforeGoogle() throws Exception {
        mvc.perform(get("/api/auth/google/callback").param("code", "valid-code").param("state", "wrong")
                        .cookie(new Cookie("oauth_state", "expected")))
                .andExpect(status().isBadRequest()).andExpect(jsonPath("$.code").value("INVALID_REQUEST"))
                .andExpect(cookie().maxAge("oauth_state", 0));
        mvc.perform(get("/api/auth/google/callback").param("code", "valid-code").param("state", "expected"))
                .andExpect(status().isBadRequest());
        mvc.perform(get("/api/auth/google/callback").param("code", "valid-code")
                        .cookie(new Cookie("oauth_state", "expected"))).andExpect(status().isBadRequest());
        mvc.perform(get("/api/auth/google/callback").param("state", "expected")
                        .cookie(new Cookie("oauth_state", "expected"))).andExpect(status().isBadRequest());
        verifyNoInteractions(google);
    }

    @Test
    void unverifiedEmailIsRejectedWithoutCreatingUserOrSession() throws Exception {
        when(google.exchange("valid-code")).thenReturn(new UserInfo("google-123", "fan@example.com", false, "Fan"));
        callback().andExpect(status().isUnauthorized()).andExpect(jsonPath("$.code").value("UNAUTHORIZED"))
                .andExpect(cookie().maxAge("oauth_state", 0));
        assertThat(count("users")).isZero();
        assertThat(count("auth_session")).isZero();
        assertThat(count("refresh_token")).isZero();
    }

    @Test
    void callbackRedirectsHomeWithHashedRefreshCookieAndNoAccessToken() throws Exception {
        var response = callback().andExpect(status().isFound())
                .andExpect(header().string("Location", "/"))
                .andExpect(content().string(""))
                .andExpect(cookie().maxAge("oauth_state", 0)).andReturn().getResponse();
        Cookie refresh = response.getCookie("refresh_token");
        assertThat(Base64.getUrlDecoder().decode(refresh.getValue())).hasSize(32);
        assertThat(refresh.isHttpOnly()).isTrue();
        assertThat(refresh.getSecure()).isTrue();
        assertThat(refresh.getPath()).isEqualTo("/api/auth");
        assertThat(refresh.getMaxAge()).isEqualTo(1_209_600);
        assertThat(response.getHeaders("Set-Cookie")).anySatisfy(value ->
                assertThat(value).startsWith("refresh_token=").contains("SameSite=Lax").doesNotContain("Domain="));
        assertThat(response.getHeaders("Set-Cookie")).hasSize(2).anySatisfy(value ->
                assertThat(value).startsWith("oauth_state=").contains("Max-Age=0", "Path=/api/auth/google"));
        assertThat(response.getHeader("Cache-Control")).contains("no-store");
        assertThat(count("users")).isEqualTo(1);
        assertThat(count("auth_session")).isEqualTo(1);
        assertThat(count("refresh_token")).isEqualTo(1);
        assertThat(jdbc.queryForObject("SELECT role FROM users", String.class)).isEqualTo("FAN");
        assertThat(jdbc.queryForObject("SELECT token_hash FROM refresh_token", byte[].class))
                .isEqualTo(MessageDigest.getInstance("SHA-256").digest(refresh.getValue().getBytes(StandardCharsets.US_ASCII)));
        assertThat(jdbc.queryForObject("SELECT consumed_at FROM refresh_token", Instant.class)).isNull();
        assertThat(jdbc.queryForObject("SELECT expires_at FROM auth_session", Instant.class))
                .isEqualTo(NOW.plusSeconds(14 * 86400));

    }

    @Test
    void existingAndDeletedUsersKeepIdentityAndRoleAndUpdateProfile() throws Exception {
        callback().andExpect(status().isFound());
        Long id = jdbc.queryForObject("SELECT id FROM users", Long.class);
        jdbc.update("UPDATE users SET role = 'ARTIST' WHERE id = ?", id);
        entityManager.clear();
        when(google.exchange("valid-code")).thenReturn(new UserInfo("google-123", "changed@example.com", true, "Changed"));
        callback().andExpect(status().isFound());
        entityManager.flush();
        assertThat(jdbc.queryForObject("SELECT email FROM users", String.class)).isEqualTo("changed@example.com");
        jdbc.update("UPDATE users SET deleted_at = ? WHERE id = ?", java.sql.Timestamp.from(NOW.minusSeconds(1)), id);
        entityManager.clear();
        callback().andExpect(status().isFound());
        entityManager.flush();
        assertThat(count("users")).isEqualTo(1);
        assertThat(jdbc.queryForObject("SELECT id FROM users", Long.class)).isEqualTo(id);
        assertThat(jdbc.queryForObject("SELECT email FROM users", String.class)).isEqualTo("changed@example.com");
        assertThat(jdbc.queryForObject("SELECT name FROM users", String.class)).isEqualTo("Changed");
        assertThat(jdbc.queryForObject("SELECT role FROM users", String.class)).isEqualTo("ARTIST");
        assertThat(jdbc.queryForObject("SELECT deleted_at FROM users", Instant.class)).isNull();
        assertThat(count("auth_session")).isEqualTo(3);
    }

    @Test
    void repeatedLoginsOfSameUserKeepAllSessionsActive() throws Exception {
        for (int i = 0; i < 6; i++) {
            when(clock.instant()).thenReturn(NOW.plusSeconds(i));
            callback().andExpect(status().isFound());
        }
        entityManager.flush();
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM auth_session WHERE revoked_at IS NULL", Long.class)).isEqualTo(6);
        assertThat(count("users")).isEqualTo(1);
    }

    @Test
    void sameEmailDoesNotLinkDifferentGoogleSubjects() throws Exception {
        callback().andExpect(status().isFound());
        when(google.exchange("valid-code")).thenReturn(new UserInfo("google-other", "fan@example.com", true, "Other"));
        callback().andExpect(status().isFound());
        assertThat(count("users")).isEqualTo(2);
        assertThat(jdbc.queryForList("SELECT role FROM users", String.class)).containsOnly("FAN");
    }

    @Test
    void incompleteOrOversizedProviderProfilesAreRejected() throws Exception {
        for (UserInfo info : new UserInfo[]{null,
                new UserInfo("google-123", "fan@example.com", null, "Fan"),
                new UserInfo("", "fan@example.com", true, "Fan"),
                new UserInfo("구글", "fan@example.com", true, "Fan"),
                new UserInfo("google-123", null, true, "Fan"),
                new UserInfo("google-123", "fan@example.com", true, "x".repeat(101))}) {
            when(google.exchange("valid-code")).thenReturn(info);
            callback().andExpect(status().isUnauthorized());
        }
        assertThat(count("users")).isZero();
        assertThat(count("auth_session")).isZero();
    }

    @Test
    void refreshAndLogoutWithoutCookieRequireReauthentication() throws Exception {
        mvc.perform(post("/api/auth/refresh")).andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value("REAUTH_REQUIRED"));
        mvc.perform(post("/api/auth/logout")).andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value("REAUTH_REQUIRED"));
    }

    private org.springframework.test.web.servlet.ResultActions callback() throws Exception {
        return mvc.perform(get("/api/auth/google/callback").param("code", "valid-code").param("state", "expected")
                .cookie(new Cookie("oauth_state", "expected")));
    }

    private long count(String table) {
        return jdbc.queryForObject("SELECT COUNT(*) FROM " + table, Long.class);
    }
}
