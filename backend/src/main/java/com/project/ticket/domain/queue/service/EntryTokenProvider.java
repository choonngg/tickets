package com.project.ticket.domain.queue.service;

import com.project.ticket.global.auth.RsaJwtKeys;
import io.jsonwebtoken.Jwts;
import java.time.Instant;
import java.util.Date;
import java.util.Map;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import org.springframework.util.Assert;

@Component
public class EntryTokenProvider {
    private final RsaJwtKeys keys;
    private final String keyId;

    public EntryTokenProvider(@Value("${ticket.queue.jwt.private-key}") String privatePem,
            @Value("${ticket.queue.jwt.public-key}") String publicPem,
            @Value("${ticket.queue.jwt.key-id}") String keyId) {
        Assert.hasText(keyId, "Queue JWT key ID is required");
        this.keys = RsaJwtKeys.fromPem(privatePem, publicPem);
        this.keyId = keyId;
    }

    public String issue(long concertId, long userId, Instant activeExpiry) {
        Assert.isTrue(concertId > 0 && userId > 0, "Queue IDs must be positive");
        Assert.notNull(activeExpiry, "ACTIVE expiry is required");
        // JWT NumericDate has second precision; truncate, never round up or extend Redis expiry.
        return Jwts.builder().header().keyId(keyId).type("JWT").and()
                .issuer("queue-service").audience().add("booking-service").and()
                .subject(Long.toString(userId)).claim("pid", Long.toString(concertId))
                .expiration(Date.from(activeExpiry)).signWith(keys.privateKey(), Jwts.SIG.RS256).compact();
    }

    public Map<String, Object> jwks() { return keys.jwks(keyId); }
}
