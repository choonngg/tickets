package com.project.ticket.domain.concert.entity;

import com.project.ticket.domain.user.entity.User;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.FetchType;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import lombok.Getter;

@Getter
@Entity
@Table(name = "concert")
public class Concert {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "artist_id", nullable = false)
    private User artist;

    @Column(nullable = false, length = 100)
    private String title;

    @Column(nullable = false, length = 255)
    private String venue;

    @Column(name = "concert_at", nullable = false)
    private Instant concertDate;

    @Column(name = "ticket_open_at", nullable = false)
    private Instant ticketOpenDate;

    @Column(name = "ticket_close_at", nullable = false)
    private Instant ticketCloseDate;

    @Column(name = "cancelled_at")
    private Instant cancelledAt;

    @Column(nullable = false, insertable = false, updatable = false,
            columnDefinition = "DATETIME(6) DEFAULT CURRENT_TIMESTAMP(6)")
    private Instant createdAt;

    @Column(nullable = false, insertable = false, updatable = false,
            columnDefinition = "DATETIME(6) DEFAULT CURRENT_TIMESTAMP(6)")
    private Instant updatedAt;

    protected Concert() {
    }

    public static Concert create(
            User artist,
            String title,
            String venue,
            Instant concertDate,
            Instant ticketOpenDate,
            Instant ticketCloseDate
    ) {
        Concert concert = new Concert();
        concert.artist = artist;
        concert.title = title;
        concert.venue = venue;
        concert.concertDate = concertDate.truncatedTo(ChronoUnit.MICROS);
        concert.ticketOpenDate = ticketOpenDate.truncatedTo(ChronoUnit.MICROS);
        concert.ticketCloseDate = ticketCloseDate.truncatedTo(ChronoUnit.MICROS);
        return concert;
    }

    public ConcertStatus statusAt(Instant now) {
        if (cancelledAt != null) {
            return ConcertStatus.CANCELLED;
        }
        if (now.isBefore(ticketOpenDate)) {
            return ConcertStatus.BEFORE_OPEN;
        }
        if (!now.isBefore(ticketCloseDate)) {
            return ConcertStatus.CLOSED;
        }
        return ConcertStatus.ON_SALE;
    }

    public boolean isOnSale(Instant now) {
        return statusAt(now) == ConcertStatus.ON_SALE;
    }
}
