package com.project.ticket.domain.ticket.service.purchase;

import com.project.ticket.domain.ticket.dto.TicketPurchaseResponse;
import java.util.List;

@FunctionalInterface
public interface TicketPurchaseStrategy {
    List<TicketPurchaseResponse> purchase(Long userId, List<Long> seatIds, String purchaseKey, String admissionId);
}
