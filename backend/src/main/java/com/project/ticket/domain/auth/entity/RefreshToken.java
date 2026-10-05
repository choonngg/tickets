package com.project.ticket.domain.auth.entity;

import jakarta.persistence.*;
import java.time.Instant;
import lombok.Getter;

@Getter
@Entity
@Table(name = "refresh_token")
public class RefreshToken {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "session_id", nullable = false)
    @org.hibernate.annotations.OnDelete(action = org.hibernate.annotations.OnDeleteAction.CASCADE)
    private AuthSession session;

    @Column(name = "token_hash", nullable = false, unique = true, columnDefinition = "BINARY(32)")
    private byte[] tokenHash;

    @Column(name = "consumed_at")
    private Instant consumedAt;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    protected RefreshToken() {}

    public static RefreshToken create(AuthSession session, byte[] hash, Instant now) {
        RefreshToken token = new RefreshToken();
        token.session = session;
        token.tokenHash = hash.clone();
        token.createdAt = now;
        return token;
    }

    public void consume(Instant now) {
        consumedAt = now;
    }
}
