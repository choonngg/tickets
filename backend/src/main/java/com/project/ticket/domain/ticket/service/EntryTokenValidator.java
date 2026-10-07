package com.project.ticket.domain.ticket.service;

import com.project.ticket.domain.seat.repository.SeatRepository;
import com.project.ticket.global.auth.RsaJwtKeys;
import com.project.ticket.global.exception.BusinessException;
import com.project.ticket.global.exception.ErrorCode;
import io.jsonwebtoken.ExpiredJwtException;
import io.jsonwebtoken.JwtException;
import io.jsonwebtoken.JwtParser;
import io.jsonwebtoken.Jwts;
import java.time.Clock;
import java.util.Date;
import java.util.Set;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import org.springframework.util.Assert;
import org.springframework.util.StringUtils;

/** Signature and request binding; Redis admission ownership is checked separately. */
@Component
public class EntryTokenValidator {
    private final JwtParser parser;
    private final String keyId;
    private final Clock clock;
    private final SeatRepository seats;

    public EntryTokenValidator(@Value("${ticket.queue.jwt.public-key}") String publicPem,
            @Value("${ticket.queue.jwt.key-id}") String keyId, Clock clock, SeatRepository seats) {
        Assert.hasText(keyId, "Queue JWT key ID is required");
        var publicKey = RsaJwtKeys.publicFromPem(publicPem);
        this.parser = Jwts.parser().keyLocator(header -> {
                    if (!"RS256".equals(header.get("alg"))) throw new JwtException("Entry token requires RS256");
                    return publicKey;
                })
                .clock(() -> Date.from(clock.instant())).build();
        this.keyId = keyId;
        this.clock = clock;
        this.seats = seats;
    }

    public record Admission(long concertId, String admissionId, java.time.Instant expiry) { }

    public Admission validate(String token, long userId, java.util.List<Long> seatIds) {
        if (!StringUtils.hasText(token)) throw new BusinessException(ErrorCode.ENTRY_TOKEN_REQUIRED);
        long tokenUser;
        long concertId;
        String admissionId;
        java.time.Instant expiresAt;
        try {
            var jwt = parser.parseSignedClaims(token);
            var claims = jwt.getPayload();
            var expiry = claims.getExpiration();
            if (expiry == null) throw new BusinessException(ErrorCode.ENTRY_TOKEN_INVALID);
            // JJWT permits equality; admission ends exactly at exp, including submillisecond clocks.
            if (!clock.instant().isBefore(expiry.toInstant())) {
                throw new BusinessException(ErrorCode.ENTRY_TOKEN_EXPIRED);
            }
            if (!keyId.equals(jwt.getHeader().getKeyId()) || !"JWT".equals(jwt.getHeader().getType())
                    || !"queue-service".equals(claims.getIssuer())
                    || !Set.of("booking-service").equals(claims.getAudience())) {
                throw new BusinessException(ErrorCode.ENTRY_TOKEN_INVALID);
            }
            admissionId = claims.get("rid", String.class);
            if (!StringUtils.hasText(admissionId) || admissionId.length() > 64) {
                throw new BusinessException(ErrorCode.ENTRY_TOKEN_INVALID);
            }
            expiresAt = expiry.toInstant();
            tokenUser = Long.parseLong(claims.getSubject());
            concertId = Long.parseLong(claims.get("pid", String.class));
            if (tokenUser <= 0 || concertId <= 0) throw new BusinessException(ErrorCode.ENTRY_TOKEN_INVALID);
        } catch (ExpiredJwtException exception) {
            throw new BusinessException(ErrorCode.ENTRY_TOKEN_EXPIRED);
        } catch (JwtException | IllegalArgumentException exception) {
            throw new BusinessException(ErrorCode.ENTRY_TOKEN_INVALID);
        }
        if (tokenUser != userId) throw new BusinessException(ErrorCode.ENTRY_TOKEN_MISMATCH);
        Long requestedConcert = null;
        for (Long seatId : seatIds) {
            var seat = seats.findById(seatId).orElseThrow(() -> new BusinessException(ErrorCode.SEAT_NOT_FOUND));
            Long currentConcert = seat.getConcert().getId();
            if (requestedConcert != null && !requestedConcert.equals(currentConcert)) {
                throw new BusinessException(ErrorCode.INVALID_REQUEST);
            }
            requestedConcert = currentConcert;
        }
        if (!requestedConcert.equals(concertId)) throw new BusinessException(ErrorCode.ENTRY_TOKEN_MISMATCH);
        return new Admission(concertId, admissionId, expiresAt);
    }
}
