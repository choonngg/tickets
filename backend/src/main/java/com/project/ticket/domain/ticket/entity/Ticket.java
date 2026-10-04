package com.project.ticket.domain.ticket.entity;

import com.project.ticket.domain.seat.entity.Seat;
import com.project.ticket.domain.user.entity.User;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.FetchType;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.OneToOne;
import jakarta.persistence.Table;
import jakarta.persistence.UniqueConstraint;
import java.time.Instant;
import lombok.Getter;

@Getter
@Entity
@Table(
        name = "ticket",
        uniqueConstraints = @UniqueConstraint(name = "uq_ticket_seat", columnNames = "seat_id")
)
public class Ticket {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "user_id", nullable = false)
    private User user;

    @OneToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "seat_id", nullable = false)
    private Seat seat;

    @Column(nullable = false)
    private int purchasePrice;

    @Column(nullable = false)
    private Instant createdAt;

    protected Ticket() {
    }

    public static Ticket complete(User user, Seat seat, int purchasePrice, Instant purchasedAt) {
        Ticket ticket = new Ticket();
        ticket.user = user;
        ticket.seat = seat;
        ticket.purchasePrice = purchasePrice;
        ticket.createdAt = purchasedAt;
        return ticket;
    }

}
