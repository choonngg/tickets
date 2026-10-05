package com.project.ticket.domain.user.repository;

import com.project.ticket.domain.user.entity.User;
import jakarta.persistence.LockModeType;
import java.time.Instant;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface UserRepository extends JpaRepository<User, Long> {
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select u from User u where u.id = :id")
    Optional<User> findByIdForUpdate(@Param("id") Long id);

    // Serialize concurrent first logins without linking accounts by email.
    @Modifying
    @Query(value = """
            INSERT INTO users (google_sub, email, name, role, created_at, updated_at)
            VALUES (:sub, :email, :name, 'FAN', :now, :now)
            ON DUPLICATE KEY UPDATE google_sub = google_sub
            """, nativeQuery = true)
    void insertGoogleUserIfAbsent(@Param("sub") String sub, @Param("email") String email,
            @Param("name") String name, @Param("now") Instant now);

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    Optional<User> findByGoogleSub(String googleSub);
}
