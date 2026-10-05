package com.project.ticket.domain.user.entity;

import jakarta.persistence.*;
import java.time.Instant;
import lombok.Getter;

@Getter
@Entity
@Table(name = "users")
public class User {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "google_sub", nullable = false, unique = true, length = 255)
    private String googleSub;

    @Column(nullable = false, length = 255)
    private String email;

    @Column(nullable = false, length = 100)
    private String name;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private UserRole role;

    @Column(name = "deleted_at")
    private Instant deletedAt;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    protected User() {}

    public static User fromGoogle(String sub, String email, String name, Instant now) {
        User user = new User();
        user.googleSub = sub;
        user.role = UserRole.FAN;
        user.createdAt = now;
        user.updateGoogleProfile(email, name, now);
        return user;
    }

    public void updateGoogleProfile(String email, String name, Instant now) {
        this.email = email;
        this.name = name;
        this.deletedAt = null;
        this.updatedAt = now;
    }

    public void delete(Instant now) {
        if (deletedAt == null) deletedAt = now;
        updatedAt = now;
    }
}
