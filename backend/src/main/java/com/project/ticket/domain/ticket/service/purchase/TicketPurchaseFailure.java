package com.project.ticket.domain.ticket.service.purchase;

import com.project.ticket.global.exception.BusinessException;
import com.project.ticket.global.exception.ErrorCode;
import org.hibernate.exception.ConstraintViolationException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.ConcurrencyFailureException;
import org.springframework.dao.DataIntegrityViolationException;

final class TicketPurchaseFailure {
    private static final Logger log = LoggerFactory.getLogger(TicketPurchaseFailure.class);

    private TicketPurchaseFailure() {
    }

    static RuntimeException translate(RuntimeException exception, Long userId, Long seatId) {
        if (exception instanceof ConcurrencyFailureException) {
            log.debug("Seat concurrency conflict for user {} and seat {}", userId, seatId, exception);
            return new BusinessException(ErrorCode.SEAT_LOCK_FAILED);
        }
        if (exception instanceof DataIntegrityViolationException) {
            for (Throwable cause = exception; cause != null; cause = cause.getCause()) {
                if (cause instanceof ConstraintViolationException violation) {
                    String name = violation.getConstraintName();
                    if ("uq_ticket_seat".equals(name) || (name != null && name.endsWith(".uq_ticket_seat"))) {
                        log.warn("uq_ticket_seat violation for user {} and seat {}", userId, seatId, exception);
                        return new BusinessException(ErrorCode.SEAT_NOT_AVAILABLE);
                    }
                }
            }
        }
        return exception;
    }
}
