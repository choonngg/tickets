package com.project.ticket.domain.ticket.dto;

import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;
import jakarta.validation.constraints.Size;
import java.util.List;
import com.project.ticket.global.exception.BusinessException;
import com.project.ticket.global.exception.ErrorCode;

public record TicketPurchaseRequest(@NotNull @Size(min = 1, max = 4) List<@NotNull @Positive Long> seatIds) {
    public List<Long> sortedSeatIds() {
        if (seatIds == null || seatIds.isEmpty() || seatIds.size() > 4
                || seatIds.stream().anyMatch(id -> id == null || id <= 0)
                || seatIds.stream().distinct().count() != seatIds.size()) {
            throw new BusinessException(ErrorCode.INVALID_REQUEST);
        }
        return seatIds.stream().sorted().toList();
    }
}
