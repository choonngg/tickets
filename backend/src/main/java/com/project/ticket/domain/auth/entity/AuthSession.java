package com.project.ticket.domain.auth.entity;

import com.project.ticket.domain.user.entity.User;
import jakarta.persistence.*;
import java.time.Instant;
import lombok.Getter;

@Getter
@Entity
@Table(name = "auth_session")
public class AuthSession {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "user_id", nullable = false)
    private User user;

    @Column(name = "expires_at", nullable = false, updatable = false)
    private Instant expiresAt;

    @Column(name = "revoked_at")
    private Instant revokedAt;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    protected AuthSession() {}

    public static AuthSession create(User user, Instant now) {
        AuthSession session = new AuthSession();
        session.user = user;
        session.createdAt = now;
        session.expiresAt = now.plusSeconds(14 * 86400);
        return session;
    }

    public void revoke(Instant now) {
        if (revokedAt == null) revokedAt = now;
    }
}
