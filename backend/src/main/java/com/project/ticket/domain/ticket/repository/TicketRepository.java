package com.project.ticket.domain.ticket.repository;

import com.project.ticket.domain.ticket.entity.Ticket;
import java.util.List;
import java.util.Optional;
import org.springframework.data.jpa.repository.EntityGraph;
import org.springframework.data.jpa.repository.JpaRepository;

public interface TicketRepository extends JpaRepository<Ticket, Long> {
    long countByUserIdAndSeatConcertId(Long userId, Long concertId);

    boolean existsByUserIdAndSeatConcertIdAndAdmissionId(Long userId, Long concertId, String admissionId);

    @EntityGraph(attributePaths = {"user", "seat", "seat.concert"})
    List<Ticket> findByUserIdAndPurchaseKeyOrderBySeatIdAsc(Long userId, String purchaseKey);

    long countBySeatId(Long seatId);

    @EntityGraph(attributePaths = {"user", "seat"})
    Optional<Ticket> findBySeatId(Long seatId);

    @EntityGraph(attributePaths = {"seat", "seat.concert"})
    List<Ticket> findByUserIdOrderByCreatedAtDesc(Long userId);
}
