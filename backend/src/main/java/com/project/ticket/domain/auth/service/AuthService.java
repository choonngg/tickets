package com.project.ticket.domain.auth.service;

import com.project.ticket.domain.auth.entity.AuthSession;
import com.project.ticket.domain.auth.entity.RefreshToken;
import com.project.ticket.domain.auth.repository.AuthSessionRepository;
import com.project.ticket.domain.auth.repository.RefreshTokenRepository;
import com.project.ticket.domain.user.repository.UserRepository;
import com.project.ticket.global.auth.JwtProvider;
import com.project.ticket.global.exception.BusinessException;
import com.project.ticket.global.exception.ErrorCode;
import com.project.ticket.infra.google.GoogleOAuthClient.UserInfo;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.Base64;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class AuthService {
    private final UserRepository users;
    private final AuthSessionRepository sessions;
    private final RefreshTokenRepository tokens;
    private final JwtProvider jwt;
    private final Clock clock;
    private final SecureRandom random = new SecureRandom();

    public AuthService(UserRepository users, AuthSessionRepository sessions, RefreshTokenRepository tokens,
            JwtProvider jwt, Clock clock) {
        this.users = users;
        this.sessions = sessions;
        this.tokens = tokens;
        this.jwt = jwt;
        this.clock = clock;
    }

    @Transactional
    public IssuedTokens authenticateGoogle(UserInfo info) {
        if (info == null || !Boolean.TRUE.equals(info.emailVerified())
                || !validText(info.sub(), 255) || !info.sub().chars().allMatch(c -> c > 32 && c < 127)
                || !validText(info.email(), 255) || !validText(info.name(), 100)) {
            throw new BusinessException(ErrorCode.UNAUTHORIZED);
        }
        var now = clock.instant().truncatedTo(ChronoUnit.MICROS);
        // Only the user row is locked (login <-> withdrawal serialization). No Session list FOR UPDATE:
        // its gap locks deadlock different users inserting into auth_session (Phase 3 review R3-1).
        users.insertGoogleUserIfAbsent(info.sub(), info.email(), info.name(), now);
        var user = users.findByGoogleSub(info.sub()).orElseThrow(() -> new BusinessException(ErrorCode.UNAUTHORIZED));
        user.updateGoogleProfile(info.email(), info.name(), now);
        var session = sessions.save(AuthSession.create(user, now));
        String raw = newRefreshToken();
        tokens.save(RefreshToken.create(session, hash(raw), now));
        return new IssuedTokens(jwt.createAccessToken(user.getId(), user.getRole(), session.getId()), raw,
                remaining(session, now));
    }

    @Transactional
    public RefreshResult refresh(String raw) {
        var session = lockSession(raw);
        if (session == null) return RefreshResult.failure(ErrorCode.REAUTH_REQUIRED);
        // Lock order: scalar RT probe -> Session FOR UPDATE -> RT FOR UPDATE.
        // Logout locks only Session; withdrawal/login lock User then Session; cleanup
        // deletes Session then cascades RT. No operation holding Session waits for User.
        // The scalar probe avoids missing-hash gap locks and stale JPA token state.
        var token = tokens.findByTokenHash(hash(raw)).orElse(null);
        var now = clock.instant().truncatedTo(ChronoUnit.MICROS);
        if (token == null || session.getRevokedAt() != null || !session.getExpiresAt().isAfter(now)) {
            return RefreshResult.failure(ErrorCode.REAUTH_REQUIRED);
        }
        if (token.getConsumedAt() != null) {
            if (!now.isAfter(token.getConsumedAt().plusSeconds(15))) {
                return RefreshResult.failure(ErrorCode.REFRESH_RETRY);
            }
            session.revoke(now);
            // Return normally so revocation commits BEFORE the controller throws a 401.
            return RefreshResult.failure(ErrorCode.REAUTH_REQUIRED);
        }
        token.consume(now);
        tokens.flush(); // UPDATE must execute before IDENTITY INSERT / UNIQUE check.
        String next = newRefreshToken();
        tokens.save(RefreshToken.create(session, hash(next), now));
        var user = session.getUser();
        return new RefreshResult(new IssuedTokens(jwt.createAccessToken(user.getId(), user.getRole(), session.getId()), next,
                remaining(session, now)),
                null);
    }

    @Transactional
    public void logout(String raw) {
        var session = lockSession(raw);
        if (session == null) throw new BusinessException(ErrorCode.REAUTH_REQUIRED);
        session.revoke(clock.instant().truncatedTo(ChronoUnit.MICROS));
    }

    @Transactional
    public void withdraw(Long userId) {
        // User lock also serializes Google login/rejoin.
        var user = users.findByIdForUpdate(userId)
                .orElseThrow(() -> new BusinessException(ErrorCode.USER_NOT_FOUND));
        var all = sessions.findAllByUserIdForUpdate(userId);
        var now = clock.instant().truncatedTo(ChronoUnit.MICROS);
        user.delete(now);
        all.forEach(session -> session.revoke(now));
    }

    private static Duration remaining(AuthSession session, Instant now) {
        var left = Duration.between(now, session.getExpiresAt());
        return left.isNegative() ? Duration.ZERO : Duration.ofSeconds(left.getSeconds());
    }

    private AuthSession lockSession(String raw) {
        if (raw == null || !raw.matches("[A-Za-z0-9_-]{43}")) return null;
        return tokens.findSessionIdByHash(hash(raw)).flatMap(sessions::findByIdForUpdate).orElse(null);
    }

    private String newRefreshToken() {
        byte[] bytes = new byte[32];
        random.nextBytes(bytes);
        return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
    }

    private static boolean validText(String value, int max) {
        return value != null && !value.isBlank() && value.codePointCount(0, value.length()) <= max;
    }

    public static byte[] hash(String raw) {
        try {
            return MessageDigest.getInstance("SHA-256").digest(raw.getBytes(StandardCharsets.US_ASCII));
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException("SHA-256 is required", exception);
        }
    }

    public record IssuedTokens(String accessToken, String refreshToken, Duration refreshMaxAge) {}

    public record RefreshResult(IssuedTokens tokens, ErrorCode error) {
        private static RefreshResult failure(ErrorCode error) {
            return new RefreshResult(null, error);
        }
    }
}
