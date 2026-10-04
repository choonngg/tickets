package com.project.ticket.domain.ticket.dto;

import com.project.ticket.domain.ticket.entity.Ticket;

public record TicketViewResponse(
        Long ticketId,
        String concertTitle,
        String section,
        int row,
        int col,
        int purchasePrice
) {
    public static TicketViewResponse from(Ticket ticket) {
        return new TicketViewResponse(
                ticket.getId(),
                ticket.getSeat().getConcert().getTitle(),
                ticket.getSeat().getSection(),
                ticket.getSeat().getRow(),
                ticket.getSeat().getCol(),
                ticket.getPurchasePrice()
        );
    }
}
