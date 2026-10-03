package com.project.ticket.domain.concert.entity;

import com.project.ticket.domain.user.entity.User;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.FetchType;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;
import lombok.Getter;

import java.time.LocalDateTime;

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

    @Column(nullable = false)
    private LocalDateTime concertDate;

    @Column(nullable = false)
    private LocalDateTime ticketOpenDate;

    @Column(nullable = false)
    private LocalDateTime ticketCloseDate;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private ConcertStatus status;

    protected Concert() {
    }

    public static Concert create(
            User artist,
            String title,
            String venue,
            LocalDateTime concertDate,
            LocalDateTime ticketOpenDate,
            LocalDateTime ticketCloseDate
    ) {
        Concert concert = new Concert();
        concert.artist = artist;
        concert.title = title;
        concert.venue = venue;
        concert.concertDate = concertDate;
        concert.ticketOpenDate = ticketOpenDate;
        concert.ticketCloseDate = ticketCloseDate;
        concert.status = ConcertStatus.ON_SALE;
        return concert;
    }

    public boolean isOnSale(LocalDateTime now) {
        return status != ConcertStatus.CANCELLED
                && !now.isBefore(ticketOpenDate)
                && now.isBefore(ticketCloseDate);
    }
}
