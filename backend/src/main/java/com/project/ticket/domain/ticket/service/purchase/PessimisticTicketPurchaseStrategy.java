package com.project.ticket.domain.ticket.service.purchase;

import com.project.ticket.domain.seat.repository.SeatRepository;
import com.project.ticket.domain.ticket.dto.TicketPurchaseResponse;
import java.util.List;
import org.springframework.dao.ConcurrencyFailureException;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Component;

@Component
public class PessimisticTicketPurchaseStrategy implements TicketPurchaseStrategy {
    private final TicketPurchaseProcessor processor;
    private final SeatRepository seatRepository;

    public PessimisticTicketPurchaseStrategy(TicketPurchaseProcessor processor, SeatRepository seatRepository) {
        this.processor = processor;
        this.seatRepository = seatRepository;
    }

    @Override
    public List<TicketPurchaseResponse> purchase(Long userId, List<Long> seatIds, String purchaseKey, String admissionId) {
        try {
            return processor.purchase(userId, seatIds, purchaseKey, admissionId, seatRepository::findByIdForUpdate);
        } catch (ConcurrencyFailureException | DataIntegrityViolationException exception) {
            throw TicketPurchaseFailure.translate(exception, userId, seatIds.getFirst());
        }
    }
}
