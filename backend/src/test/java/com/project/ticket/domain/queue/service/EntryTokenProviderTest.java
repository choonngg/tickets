package com.project.ticket.domain.queue.service;

import static org.assertj.core.api.Assertions.*;
import com.project.ticket.global.auth.JwtProvider;
import com.project.ticket.global.auth.RsaJwtKeys;
import com.project.ticket.support.TestJwtKeys;
import io.jsonwebtoken.Jwts;
import java.security.KeyPairGenerator;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Base64;
import java.util.Date;
import org.junit.jupiter.api.Test;

class EntryTokenProviderTest {
    @Test void supportsBothPemNewlineFormsAndPreservesAbsoluteExpiryAtSecondPrecision() throws Exception {
        var generator = KeyPairGenerator.getInstance("RSA");
        generator.initialize(2048);
        var pair = generator.generateKeyPair();
        String privatePem = pem("PRIVATE KEY", pair.getPrivate().getEncoded());
        String publicPem = pem("PUBLIC KEY", pair.getPublic().getEncoded());
        Instant now = Instant.parse("2030-01-01T00:00:00Z");
        Instant expiry = now.plusSeconds(300).plusMillis(999);
        var login = new JwtProvider(TestJwtKeys.PRIVATE_PEM, TestJwtKeys.PUBLIC_PEM, TestJwtKeys.KEY_ID,
                TestJwtKeys.ISSUER, TestJwtKeys.AUDIENCE, Clock.fixed(now, ZoneOffset.UTC));
        for (boolean escaped : new boolean[]{false, true}) {
            var provider = new EntryTokenProvider(escaped ? privatePem.replace("\n", "\\n") : privatePem,
                    escaped ? publicPem.replace("\n", "\\n") : publicPem, "queue-key-1");
            String token = provider.issue(123, 1, expiry, "1");
            var parsed = Jwts.parser().verifyWith(RsaJwtKeys.fromPem(privatePem, publicPem).publicKey())
                    .clock(() -> Date.from(now)).build().parseSignedClaims(token);
            assertThat(parsed.getPayload().getExpiration().toInstant()).isEqualTo(now.plusSeconds(300));
            assertThat(parsed.getPayload().getExpiration().toInstant()).isBeforeOrEqualTo(expiry);
            assertThat(parsed.getPayload()).doesNotContainKeys("typ", "sid", "role");
            assertThatThrownBy(() -> login.parse(token)).isInstanceOf(io.jsonwebtoken.JwtException.class);
        }
    }

    @Test void accessParserRejectsQueueClaimsEvenIfWronglySignedWithLoginKey() {
        Instant now = Instant.parse("2030-01-01T00:00:00Z");
        var login = new JwtProvider(TestJwtKeys.PRIVATE_PEM, TestJwtKeys.PUBLIC_PEM, TestJwtKeys.KEY_ID,
                TestJwtKeys.ISSUER, TestJwtKeys.AUDIENCE, Clock.fixed(now, ZoneOffset.UTC));
        var queue = new EntryTokenProvider(TestJwtKeys.PRIVATE_PEM, TestJwtKeys.PUBLIC_PEM, TestJwtKeys.KEY_ID);
        assertThatThrownBy(() -> login.parse(queue.issue(123, 1, now.plusSeconds(300), "1")))
                .isInstanceOf(io.jsonwebtoken.JwtException.class);
    }

    private String pem(String label, byte[] bytes) {
        return "-----BEGIN " + label + "-----\n" + Base64.getEncoder().encodeToString(bytes)
                + "\n-----END " + label + "-----";
    }
}
