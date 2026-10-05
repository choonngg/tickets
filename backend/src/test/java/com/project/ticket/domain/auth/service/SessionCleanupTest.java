package com.project.ticket.domain.auth.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.when;

import java.time.Clock;
import java.time.Instant;
import java.util.UUID;
import com.project.ticket.infra.google.GoogleOAuthClient.UserInfo;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.transaction.annotation.Transactional;

@SpringBootTest
@ActiveProfiles("test")
@Transactional
class SessionCleanupTest {
    @Autowired AuthService auth;
    @Autowired SessionCleanup cleanup;
    @Autowired JdbcTemplate jdbc;
    @MockitoBean Clock clock;

    @Test
    void keepsHistoryUntilSevenDaysAfterAbsoluteExpiryAndCascadesTokens() {
        Instant now = Instant.parse("2026-10-05T03:00:00Z");
        when(clock.instant()).thenReturn(now);
        var login = auth.authenticateGoogle(new UserInfo(UUID.randomUUID().toString(), "cleanup@example.com", true, "Fan"));
        Long sid = jdbc.queryForObject("SELECT session_id FROM refresh_token WHERE token_hash = ?", Long.class, AuthService.hash(login.refreshToken()));
        auth.refresh(login.refreshToken());
        auth.logout(login.refreshToken()); // Revoked sessions must still retain history until expiry + 7d.
        Instant retentionEnd = now.plusSeconds(21 * 86400);
        when(clock.instant()).thenReturn(retentionEnd.minusNanos(1000));
        cleanup.clean();
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM auth_session WHERE id = ?", Long.class, sid)).isEqualTo(1);
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM refresh_token WHERE session_id = ?", Long.class, sid)).isEqualTo(2);
        when(clock.instant()).thenReturn(retentionEnd);
        cleanup.clean();
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM auth_session WHERE id = ?", Long.class, sid)).isZero();
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM refresh_token WHERE session_id = ?", Long.class, sid)).isZero();
        var scheduled = java.util.Arrays.stream(SessionCleanup.class.getMethods())
                .filter(method -> method.getName().equals("clean")).findFirst().orElseThrow()
                .getAnnotation(org.springframework.scheduling.annotation.Scheduled.class);
        assertThat(scheduled.cron()).isEqualTo("0 0 3 * * *");
        assertThat(scheduled.zone()).isEqualTo("UTC");
    }
}
