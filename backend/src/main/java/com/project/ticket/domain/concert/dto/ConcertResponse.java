package com.project.ticket.domain.concert.dto;

import com.project.ticket.domain.concert.entity.Concert;
import com.project.ticket.domain.concert.entity.ConcertStatus;
import java.time.Clock;
import java.time.Instant;

public record ConcertResponse(
        Long concertId,
        Long artistId,
        String title,
        String venue,
        Instant concertDate,
        Instant ticketOpenDate,
        Instant ticketCloseDate,
        ConcertStatus status
) {
    public static ConcertResponse from(Concert concert, Clock clock) {
        return new ConcertResponse(
                concert.getId(),
                concert.getArtist().getId(),
                concert.getTitle(),
                concert.getVenue(),
                concert.getConcertDate(),
                concert.getTicketOpenDate(),
                concert.getTicketCloseDate(),
                concert.statusAt(clock.instant())
        );
    }
}
