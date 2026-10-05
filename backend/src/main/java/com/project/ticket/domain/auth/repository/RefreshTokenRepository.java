package com.project.ticket.domain.auth.repository;

import com.project.ticket.domain.auth.entity.RefreshToken;
import jakarta.persistence.LockModeType;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface RefreshTokenRepository extends JpaRepository<RefreshToken, Long> {
    // Scalar probe avoids caching a stale consumed_at in the persistence context.
    @Query("select t.session.id from RefreshToken t where t.tokenHash = :hash")
    Optional<Long> findSessionIdByHash(@Param("hash") byte[] hash);

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    Optional<RefreshToken> findByTokenHash(byte[] hash);
}
