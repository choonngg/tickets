package com.project.ticket.domain.ticket.dto;

import com.project.ticket.domain.ticket.entity.Ticket;

public record TicketPurchaseResponse(
        Long ticketId,
        Long userId,
        Long seatId,
        int purchasePrice
) {
    public static TicketPurchaseResponse from(Ticket ticket) {
        return new TicketPurchaseResponse(
                ticket.getId(),
                ticket.getUser().getId(),
                ticket.getSeat().getId(),
                ticket.getPurchasePrice()
        );
    }
}
