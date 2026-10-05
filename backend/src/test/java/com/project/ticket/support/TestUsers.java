package com.project.ticket.support;

import com.project.ticket.domain.user.entity.User;
import com.project.ticket.domain.user.entity.UserRole;
import java.time.Instant;
import java.util.UUID;
import org.springframework.test.util.ReflectionTestUtils;

public final class TestUsers {
    public static User fan(String name) {
        String sub = UUID.randomUUID().toString();
        return User.fromGoogle(sub, sub + "@example.com", name, Instant.parse("2026-10-04T03:00:00Z"));
    }

    public static User artist(String name) {
        User user = fan(name);
        ReflectionTestUtils.setField(user, "role", UserRole.ARTIST);
        return user;
    }

    private TestUsers() {}
}
