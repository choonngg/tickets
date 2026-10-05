package com.project.ticket;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;
import com.project.ticket.infra.google.GoogleOAuthClient;
import jakarta.servlet.http.Cookie;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import com.project.ticket.domain.auth.service.AuthService;
import com.project.ticket.infra.google.GoogleOAuthClient.UserInfo;
import com.project.ticket.support.IntegrationTestSupport;
import jakarta.persistence.EntityManager;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.annotation.Transactional;

@AutoConfigureMockMvc
class GoogleSessionIntegrationTest extends IntegrationTestSupport {
    @Autowired AuthService auth;
    @Autowired MockMvc mvc;
    @MockitoBean GoogleOAuthClient google;
    @Autowired JdbcTemplate jdbc;
    @Autowired EntityManager entityManager;

    @Test
    @Transactional
    void googleCallbackRedirectsWithoutAccessTokenThenRefreshReturnsIt() throws Exception {
        String sub = UUID.randomUUID().toString();
        when(google.exchange("code")).thenReturn(new UserInfo(sub, "fan@example.com", true, "Fan"));
        var response = mvc.perform(get("/api/auth/google/callback").param("state", "state").param("code", "code")
                        .cookie(new Cookie("oauth_state", "state")))
                .andExpect(status().isFound()).andExpect(header().string("Location", "/"))
                .andExpect(content().string("")).andExpect(cookie().maxAge("oauth_state", 0))
                .andReturn().getResponse();
        Cookie rt = response.getCookie("refresh_token");
        assertThat(rt).isNotNull();
        entityManager.flush();
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM auth_session s JOIN users u ON u.id = s.user_id WHERE u.google_sub = ?", Long.class, sub))
                .isEqualTo(1);
        assertThat(jdbc.queryForObject("SELECT token_hash FROM refresh_token WHERE token_hash = ?", byte[].class, AuthService.hash(rt.getValue())))
                .isEqualTo(AuthService.hash(rt.getValue()));
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM refresh_token WHERE token_hash = ? AND consumed_at IS NULL AND unconsumed_session_id = session_id", Long.class, AuthService.hash(rt.getValue())))
                .isEqualTo(1);
        mvc.perform(post("/api/auth/refresh").cookie(rt)).andExpect(status().isOk())
                .andExpect(jsonPath("$.accessToken").isNotEmpty()).andExpect(jsonPath("$.tokenType").value("Bearer"));
    }
}
