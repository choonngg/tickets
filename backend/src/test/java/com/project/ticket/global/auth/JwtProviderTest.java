package com.project.ticket.global.auth;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.project.ticket.domain.user.entity.UserRole;
import io.jsonwebtoken.ExpiredJwtException;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import org.junit.jupiter.api.Test;

class JwtProviderTest {

    @Test
    void createsAndParsesAccessToken() {
        JwtProvider provider = new JwtProvider(
                "local-development-secret-key-must-be-at-least-32-bytes",
                30,
                7,
                Clock.fixed(Instant.parse("2026-10-04T03:00:00Z"), ZoneOffset.UTC)
        );

        String token = provider.createAccessToken(1L, UserRole.FAN);
        AuthenticatedUser user = provider.parse(token);

        assertThat(user.userId()).isEqualTo(1L);
        assertThat(user.role()).isEqualTo(UserRole.FAN);
    }

    @Test
    void usesInjectedClockForTokenIssuanceAndExpiry() {
        Clock clock = mock(Clock.class);
        Instant now = Instant.parse("2026-01-01T00:00:00Z");
        when(clock.instant()).thenReturn(now);
        JwtProvider provider = new JwtProvider(
                "local-development-secret-key-must-be-at-least-32-bytes", 30, 7, clock);
        String access = provider.createAccessToken(1L, UserRole.FAN);
        String refresh = provider.createRefreshToken(1L);
        assertThat(provider.parse(access).userId()).isEqualTo(1L);
        assertThat(provider.parseUserId(refresh)).isEqualTo(1L);

        when(clock.instant()).thenReturn(now.plusSeconds(30 * 60 + 1));
        assertThatThrownBy(() -> provider.parse(access)).isInstanceOf(ExpiredJwtException.class);
        assertThat(provider.parseUserId(refresh)).isEqualTo(1L);
        when(clock.instant()).thenReturn(now.plusSeconds(7 * 24 * 60 * 60 + 1));
        assertThatThrownBy(() -> provider.parseUserId(refresh)).isInstanceOf(ExpiredJwtException.class);
    }
}
