package com.project.ticket.global.auth;

import static com.project.ticket.support.TestJwtKeys.*;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.project.ticket.domain.user.entity.UserRole;
import io.jsonwebtoken.ExpiredJwtException;
import io.jsonwebtoken.Jwts;
import java.security.KeyPairGenerator;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Base64;
import org.junit.jupiter.api.Test;

class JwtProviderTest {
    private static final Instant NOW = Instant.parse("2026-10-04T03:00:00Z");

    @Test
    void createsRs256AccessTokenWithRequiredClaims() {
        JwtProvider provider = provider(Clock.fixed(NOW, ZoneOffset.UTC));
        String token = provider.createAccessToken(1L, UserRole.FAN, 1L);
        var jwt = Jwts.parser().verifyWith(PUBLIC_KEY).clock(() -> java.util.Date.from(NOW))
                .build().parseSignedClaims(token);
        assertThat(jwt.getHeader().getAlgorithm()).isEqualTo("RS256");
        assertThat(jwt.getHeader().getKeyId()).isEqualTo(KEY_ID);
        assertThat(jwt.getHeader().getType()).isEqualTo("JWT");
        assertThat(jwt.getPayload().getIssuer()).isEqualTo(ISSUER);
        assertThat(jwt.getPayload().getAudience()).containsExactly(AUDIENCE);
        assertThat(jwt.getPayload().getSubject()).isEqualTo("1");
        assertThat(jwt.getPayload()).containsEntry("sid", "1").containsEntry("typ", "AT")
                .containsEntry("role", "FAN");
        assertThat(jwt.getPayload().getIssuedAt().toInstant()).isEqualTo(NOW);
        assertThat(jwt.getPayload().getExpiration().toInstant()).isEqualTo(NOW.plusSeconds(900));
        assertThat(provider.parse(token)).isEqualTo(new AuthenticatedUser(1L, UserRole.FAN));
    }

    @Test
    void usesInjectedClockAndAllowsExactlyThirtySecondsOfAccessTokenSkew() {
        Clock clock = mock(Clock.class);
        when(clock.instant()).thenReturn(NOW);
        JwtProvider provider = provider(clock);
        String access = provider.createAccessToken(1L, UserRole.FAN, 1L);
        when(clock.instant()).thenReturn(NOW.plusSeconds(929));
        assertThat(provider.parse(access).userId()).isEqualTo(1L);
        when(clock.instant()).thenReturn(NOW.plusSeconds(930));
        assertThat(provider.parse(access).userId()).isEqualTo(1L);
        when(clock.instant()).thenReturn(NOW.plusSeconds(931));
        assertThatThrownBy(() -> provider.parse(access)).isInstanceOf(ExpiredJwtException.class);
    }

    @Test
    void supportsEscapedPemNewlines() {
        JwtProvider provider = new JwtProvider(PRIVATE_PEM.replace("\n", "\\n"),
                PUBLIC_PEM.replace("\n", "\\n"), KEY_ID, ISSUER, AUDIENCE, Clock.fixed(NOW, ZoneOffset.UTC));
        assertThat(provider.parse(provider.createAccessToken(1L, UserRole.FAN, 1L)).userId()).isEqualTo(1L);
    }

    @Test
    void rejectsMismatchedOrWeakRsaKeysAtStartup() throws Exception {
        KeyPairGenerator generator = KeyPairGenerator.getInstance("RSA");
        generator.initialize(2048);
        String otherPublic = "-----BEGIN PUBLIC KEY-----\n"
                + Base64.getEncoder().encodeToString(generator.generateKeyPair().getPublic().getEncoded())
                + "\n-----END PUBLIC KEY-----";
        assertThatThrownBy(() -> new JwtProvider(PRIVATE_PEM, otherPublic,
                KEY_ID, ISSUER, AUDIENCE, Clock.systemUTC())).hasMessageContaining("must match");
        generator.initialize(1024);
        var weak = generator.generateKeyPair();
        String weakPrivate = "-----BEGIN PRIVATE KEY-----\n"
                + Base64.getEncoder().encodeToString(weak.getPrivate().getEncoded()) + "\n-----END PRIVATE KEY-----";
        String weakPublic = "-----BEGIN PUBLIC KEY-----\n"
                + Base64.getEncoder().encodeToString(weak.getPublic().getEncoded()) + "\n-----END PUBLIC KEY-----";
        assertThatThrownBy(() -> new JwtProvider(weakPrivate, weakPublic,
                KEY_ID, ISSUER, AUDIENCE, Clock.systemUTC())).hasMessageContaining("2048");
    }
}
