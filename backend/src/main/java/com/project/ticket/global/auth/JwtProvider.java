package com.project.ticket.global.auth;

import com.project.ticket.domain.user.entity.UserRole;
import io.jsonwebtoken.Claims;
import io.jsonwebtoken.JwtException;
import io.jsonwebtoken.JwtParser;
import io.jsonwebtoken.Jwts;
import java.security.interfaces.RSAPrivateKey;
import java.security.interfaces.RSAPublicKey;
import java.time.Clock;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.Date;
import java.util.Map;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import org.springframework.util.Assert;
import org.springframework.util.StringUtils;

@Component
public class JwtProvider {
    private final RSAPrivateKey privateKey;
    private final String keyId;
    private final String issuer;
    private final String audience;
    private final Clock clock;
    private final JwtParser accessParser;
    private final Map<String, Object> jwks;

    public JwtProvider(
            @Value("${jwt.private-key}") String privateKeyPem,
            @Value("${jwt.public-key}") String publicKeyPem,
            @Value("${jwt.key-id}") String keyId,
            @Value("${jwt.issuer}") String issuer,
            @Value("${jwt.audience}") String audience,
            Clock clock
    ) {
        Assert.hasText(keyId, "JWT key ID is required");
        Assert.hasText(issuer, "JWT issuer is required");
        Assert.hasText(audience, "JWT audience is required");
        RsaJwtKeys keys = RsaJwtKeys.fromPem(privateKeyPem, publicKeyPem);
        this.privateKey = keys.privateKey();
        RSAPublicKey publicKey = keys.publicKey();
        this.keyId = keyId;
        this.issuer = issuer;
        this.audience = audience;
        this.clock = clock;
        this.accessParser = Jwts.parser()
                .verifyWith(publicKey)
                .requireIssuer(issuer)
                .requireAudience(audience)
                .require("typ", "AT")
                .clock(() -> Date.from(clock.instant()))
                .clockSkewSeconds(30)
                .build();
        this.jwks = keys.jwks(keyId);
    }

    public String createAccessToken(Long userId, UserRole role, Long sessionId) {
        Assert.isTrue(userId != null && userId > 0 && sessionId != null && sessionId > 0, "Valid user and session IDs are required");
        Instant now = clock.instant();
        return Jwts.builder()
                .header().keyId(keyId).type("JWT").and()
                .issuer(issuer)
                .audience().add(audience).and()
                .subject(String.valueOf(userId))
                .claim("sid", sessionId.toString())
                .claim("typ", "AT")
                .claim("role", role.name())
                .issuedAt(Date.from(now))
                .expiration(Date.from(now.plus(15, ChronoUnit.MINUTES)))
                .signWith(privateKey, Jwts.SIG.RS256)
                .compact();
    }

    public AuthenticatedUser parse(String token) {
        var jwt = accessParser.parseSignedClaims(token);
        Claims claims = jwt.getPayload();
        if (!"RS256".equals(jwt.getHeader().getAlgorithm()) || !keyId.equals(jwt.getHeader().getKeyId())
                || claims.getExpiration() == null || claims.getIssuedAt() == null
                || !StringUtils.hasText(claims.get("sid", String.class))
                || !StringUtils.hasText(claims.get("role", String.class))) {
            throw new JwtException("Missing or invalid access token claims");
        }
        Long userId = Long.valueOf(claims.getSubject());
        if (userId <= 0) {
            throw new JwtException("Invalid access token subject");
        }
        if (Long.parseLong(claims.get("sid", String.class)) <= 0) {
            throw new JwtException("Invalid access token session");
        }
        UserRole role = UserRole.valueOf(claims.get("role", String.class));
        return new AuthenticatedUser(userId, role);
    }

    public Map<String, Object> jwks() {
        return jwks;
    }
}
