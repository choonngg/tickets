package com.project.ticket.domain.ticket.service.purchase;

import com.project.ticket.domain.seat.repository.SeatRepository;
import com.project.ticket.domain.ticket.dto.TicketPurchaseResponse;
import com.project.ticket.infra.lock.SeatLockManager;
import org.springframework.dao.ConcurrencyFailureException;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Component;

@Component
public class DistributedTicketPurchaseStrategy implements TicketPurchaseStrategy {
    private final TicketPurchaseProcessor processor;
    private final SeatRepository seatRepository;
    private final SeatLockManager seatLockManager;

    public DistributedTicketPurchaseStrategy(
            TicketPurchaseProcessor processor,
            SeatRepository seatRepository,
            SeatLockManager seatLockManager
    ) {
        this.processor = processor;
        this.seatRepository = seatRepository;
        this.seatLockManager = seatLockManager;
    }

    @Override
    public TicketPurchaseResponse purchase(Long userId, Long seatId) {
        try {
            return seatLockManager.executeWithLock(
                    seatId,
                    () -> processor.purchase(userId, seatId, seatRepository::findById)
            );
        } catch (ConcurrencyFailureException | DataIntegrityViolationException exception) {
            throw TicketPurchaseFailure.translate(exception, userId, seatId);
        }
    }
}
