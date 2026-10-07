package com.project.ticket.support;

import com.project.ticket.domain.queue.service.EntryTokenProvider;
import com.project.ticket.domain.seat.repository.SeatRepository;
import com.project.ticket.global.auth.RsaJwtKeys;
import io.jsonwebtoken.JwtBuilder;
import io.jsonwebtoken.Jwts;
import java.time.Clock;
import java.time.Instant;
import java.util.Date;
import org.springframework.beans.factory.config.YamlPropertiesFactoryBean;
import org.springframework.core.io.ClassPathResource;

/** Real queue signatures with the source set's dedicated test keys. */
public final class TestEntryTokens {
    public static final String PUBLIC_PEM;
    public static final String KEY_ID;
    public static final RsaJwtKeys KEYS;
    private static final EntryTokenProvider PROVIDER;

    static {
        var yaml = new YamlPropertiesFactoryBean();
        yaml.setResources(new ClassPathResource("application-test.yaml"));
        var properties = yaml.getObject();
        String privatePem = properties.getProperty("ticket.queue.jwt.private-key");
        PUBLIC_PEM = properties.getProperty("ticket.queue.jwt.public-key");
        KEY_ID = properties.getProperty("ticket.queue.jwt.key-id");
        KEYS = RsaJwtKeys.fromPem(privatePem, PUBLIC_PEM);
        PROVIDER = new EntryTokenProvider(privatePem, PUBLIC_PEM, KEY_ID);
    }

    public static String issue(long concertId, long userId, Clock clock) {
        Instant expiry = clock.instant().plusSeconds(300).truncatedTo(java.time.temporal.ChronoUnit.SECONDS);
        return PROVIDER.issue(concertId, userId, expiry,
                InMemoryEntryAdmissionStore.grant(concertId, userId, expiry));
    }

    public static String forSeat(SeatRepository seats, long seatId, long userId, Clock clock) {
        return issue(seats.findById(seatId).orElseThrow().getConcert().getId(), userId, clock);
    }

    public static JwtBuilder builder(long concertId, long userId, Instant expiry) {
        return Jwts.builder().header().keyId(KEY_ID).type("JWT").and()
                .issuer("queue-service").audience().add("booking-service").and()
                .subject(Long.toString(userId)).claim("pid", Long.toString(concertId))
                .claim("rid", "test-admission").expiration(Date.from(expiry));
    }

    public static String sign(JwtBuilder builder) {
        return builder.signWith(KEYS.privateKey(), Jwts.SIG.RS256).compact();
    }

    private TestEntryTokens() {}
}
