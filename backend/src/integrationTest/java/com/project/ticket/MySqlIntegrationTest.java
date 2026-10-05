package com.project.ticket;

import com.project.ticket.support.TestUsers;

import static org.assertj.core.api.Assertions.assertThat;

import com.project.ticket.domain.user.entity.User;
import com.project.ticket.domain.user.entity.UserRole;
import com.project.ticket.domain.user.repository.UserRepository;
import com.project.ticket.support.IntegrationTestSupport;
import jakarta.persistence.EntityManager;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.transaction.annotation.Transactional;

class MySqlIntegrationTest extends IntegrationTestSupport {
    @Autowired
    UserRepository users;

    @Autowired
    EntityManager entityManager;

    @Test
    @Transactional
    void savesAndReadsUserFromMySql() {
        User saved = users.saveAndFlush(TestUsers.fan("Integration Fan"));
        Long id = saved.getId();
        entityManager.clear();

        User loaded = users.findById(id).orElseThrow();

        assertThat(id).isPositive();
        assertThat(loaded).isNotSameAs(saved);
        assertThat(loaded.getName()).isEqualTo("Integration Fan");
        assertThat(loaded.getRole()).isEqualTo(UserRole.FAN);
    }
}
