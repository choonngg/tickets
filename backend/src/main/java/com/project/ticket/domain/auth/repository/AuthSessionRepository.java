package com.project.ticket.domain.auth.repository;

import com.project.ticket.domain.auth.entity.AuthSession;
import jakarta.persistence.LockModeType;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface AuthSessionRepository extends JpaRepository<AuthSession, Long> {
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select s from AuthSession s where s.id = :id")
    Optional<AuthSession> findByIdForUpdate(@Param("id") Long id);

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select s from AuthSession s where s.user.id = :userId order by s.id")
    List<AuthSession> findAllByUserIdForUpdate(@Param("userId") Long userId);

    @Modifying
    @Query(value = "DELETE FROM auth_session WHERE expires_at <= :cutoff", nativeQuery = true)
    int deleteExpiredBefore(@Param("cutoff") Instant cutoff);
}
