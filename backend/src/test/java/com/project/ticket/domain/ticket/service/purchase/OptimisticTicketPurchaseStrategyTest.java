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
import com.project.ticket.global.exception.GlobalExceptionHandler;
import com.project.ticket.support.InMemorySeatLockManager;
import java.sql.SQLException;
import java.util.List;
import org.hibernate.exception.ConstraintViolationException;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.springframework.dao.CannotAcquireLockException;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.orm.ObjectOptimisticLockingFailureException;

class OptimisticTicketPurchaseStrategyTest {
    @ParameterizedTest
    @EnumSource(PurchaseStrategy.class)
    void ticketSeatUniqueViolationReturns409SeatNotAvailable(PurchaseStrategy strategy) {
        for (String name : List.of("uq_ticket_seat", "ticket.uq_ticket_seat")) {
            assertConflict(strategy, integrityViolation(name), ErrorCode.SEAT_NOT_AVAILABLE);
        }
    }

    @ParameterizedTest
    @EnumSource(PurchaseStrategy.class)
    void versionConflictAndDeadlockReturn409SeatLockFailed(PurchaseStrategy strategy) {
        assertConflict(strategy, new ObjectOptimisticLockingFailureException("Seat", 2L), ErrorCode.SEAT_LOCK_FAILED);
        assertConflict(strategy, new CannotAcquireLockException("Deadlock"), ErrorCode.SEAT_LOCK_FAILED);
    }

    @ParameterizedTest
    @EnumSource(PurchaseStrategy.class)
    void otherIntegrityViolationsKeepExisting400Handling(PurchaseStrategy strategy) {
        for (String name : List.of("uq_seat_position", "fk_ticket_user", "uq_ticket_seat_other")) {
            var failure = integrityViolation(name);
            assertThatThrownBy(() -> failingStrategy(strategy, failure).purchase(1L, 2L)).isSameAs(failure);
        }
        var failure = new DataIntegrityViolationException("Unknown integrity violation");
        assertThatThrownBy(() -> failingStrategy(strategy, failure).purchase(1L, 2L)).isSameAs(failure);
        assertThat(new GlobalExceptionHandler().handleValidationException().getStatusCode().value()).isEqualTo(400);
    }

    private DataIntegrityViolationException integrityViolation(String constraint) {
        return new DataIntegrityViolationException("Insert failed", new ConstraintViolationException(
                "Duplicate entry", new SQLException("Duplicate entry", "23000", 1062), constraint));
    }

    private void assertConflict(PurchaseStrategy strategy, RuntimeException failure, ErrorCode expected) {
        assertThatThrownBy(() -> failingStrategy(strategy, failure).purchase(1L, 2L))
                .isInstanceOfSatisfying(BusinessException.class, exception -> {
                    var response = new GlobalExceptionHandler().handleBusinessException(exception);
                    assertThat(response.getStatusCode().value()).isEqualTo(409);
                    assertThat(response.getBody().code()).isEqualTo(expected.name());
                });
    }

    private TicketPurchaseStrategy failingStrategy(PurchaseStrategy strategy, RuntimeException failure) {
        TicketPurchaseProcessor processor = mock(TicketPurchaseProcessor.class);
        SeatRepository seats = mock(SeatRepository.class);
        when(processor.purchase(eq(1L), eq(2L), any())).thenThrow(failure);
        return switch (strategy) {
            case OPTIMISTIC -> new OptimisticTicketPurchaseStrategy(processor, seats);
            case PESSIMISTIC -> new PessimisticTicketPurchaseStrategy(processor, seats);
            case DISTRIBUTED -> new DistributedTicketPurchaseStrategy(processor, seats, new InMemorySeatLockManager());
        };
    }

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
