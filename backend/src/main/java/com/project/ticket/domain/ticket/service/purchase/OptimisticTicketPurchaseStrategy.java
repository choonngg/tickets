package com.project.ticket.domain.ticket.service.purchase;

import com.project.ticket.domain.seat.repository.SeatRepository;
import com.project.ticket.domain.ticket.dto.TicketPurchaseResponse;
import org.springframework.dao.ConcurrencyFailureException;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Component;

@Component
public class OptimisticTicketPurchaseStrategy implements TicketPurchaseStrategy {
    private final TicketPurchaseProcessor processor;
    private final SeatRepository seatRepository;

    public OptimisticTicketPurchaseStrategy(TicketPurchaseProcessor processor, SeatRepository seatRepository) {
        this.processor = processor;
        this.seatRepository = seatRepository;
    }

    @Override
    public TicketPurchaseResponse purchase(Long userId, Long seatId) {
        try {
            return processor.purchase(userId, seatId, seatRepository::findById);
        } catch (ConcurrencyFailureException | DataIntegrityViolationException exception) {
            throw TicketPurchaseFailure.translate(exception, userId, seatId);
        }
    }
}
