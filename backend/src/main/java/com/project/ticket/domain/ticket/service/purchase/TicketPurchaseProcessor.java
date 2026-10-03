package com.project.ticket.domain.ticket.service.purchase;

import com.project.ticket.domain.seat.entity.Seat;
import com.project.ticket.domain.ticket.dto.TicketPurchaseResponse;
import com.project.ticket.domain.ticket.entity.Ticket;
import com.project.ticket.domain.ticket.repository.TicketRepository;
import com.project.ticket.domain.user.entity.User;
import com.project.ticket.domain.user.repository.UserRepository;
import com.project.ticket.global.exception.BusinessException;
import com.project.ticket.global.exception.ErrorCode;
import com.project.ticket.infra.payment.PaymentClient;
import com.project.ticket.infra.payment.PaymentResult;
import java.time.Clock;
import java.time.LocalDateTime;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

@Component
public class TicketPurchaseProcessor {
    private static final Logger log = LoggerFactory.getLogger(TicketPurchaseProcessor.class);
    private final TicketRepository ticketRepository;
    private final UserRepository userRepository;
    private final PaymentClient paymentClient;
    private final Clock clock;

    public TicketPurchaseProcessor(
            TicketRepository ticketRepository,
            UserRepository userRepository,
            PaymentClient paymentClient,
            Clock clock
    ) {
        this.ticketRepository = ticketRepository;
        this.userRepository = userRepository;
        this.paymentClient = paymentClient;
        this.clock = clock;
    }

    @Transactional
    public TicketPurchaseResponse purchase(Long userId, Long seatId, SeatReader seatReader) {
        User user = userRepository.findById(userId)
                .filter(found -> !found.isDeleted())
                .orElseThrow(() -> new BusinessException(ErrorCode.USER_NOT_FOUND));
        Seat seat = seatReader.find(seatId)
                .orElseThrow(() -> new BusinessException(ErrorCode.SEAT_NOT_FOUND));

        if (!seat.getConcert().isOnSale(LocalDateTime.now(clock))) {
            throw new BusinessException(ErrorCode.CONCERT_NOT_ON_SALE);
        }

        seat.hold();
        PaymentResult paymentResult = paymentClient.pay(user, seat, seat.getPrice());
        if (!paymentResult.successful()) {
            log.warn("Payment failed for user {} and seat {}", userId, seatId);
            throw new BusinessException(ErrorCode.PAYMENT_FAILED);
        }

        seat.completeSale();
        Ticket ticket = ticketRepository.save(Ticket.complete(user, seat, paymentResult.payment()));
        return TicketPurchaseResponse.from(ticket);
    }
}
