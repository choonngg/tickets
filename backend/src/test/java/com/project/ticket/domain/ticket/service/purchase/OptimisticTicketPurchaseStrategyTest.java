package com.project.ticket.domain.ticket.service.purchase;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.project.ticket.domain.seat.repository.SeatRepository;
import com.project.ticket.global.exception.BusinessException;
import com.project.ticket.global.exception.ErrorCode;
import org.junit.jupiter.api.Test;
import org.springframework.dao.CannotAcquireLockException;

class OptimisticTicketPurchaseStrategyTest {
    @Test
    void databaseDeadlockReturnsExistingSeatContentionError() {
        TicketPurchaseProcessor processor = mock(TicketPurchaseProcessor.class);
        SeatRepository seats = mock(SeatRepository.class);
        when(processor.purchase(eq(1L), eq(2L), any()))
                .thenThrow(new CannotAcquireLockException("Deadlock found when trying to get lock"));
        var strategy = new OptimisticTicketPurchaseStrategy(processor, seats);

        assertThatThrownBy(() -> strategy.purchase(1L, 2L))
                .isInstanceOfSatisfying(BusinessException.class,
                        exception -> assertThat(exception.errorCode()).isEqualTo(ErrorCode.SEAT_LOCK_FAILED));
    }
}
