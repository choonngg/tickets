package com.project.ticket.domain.ticket.service.purchase;

import com.project.ticket.domain.seat.entity.Seat;
import com.project.ticket.domain.seat.entity.SeatStatus;
import com.project.ticket.domain.ticket.dto.TicketPurchaseResponse;
import com.project.ticket.domain.ticket.entity.Ticket;
import com.project.ticket.domain.ticket.repository.TicketRepository;
import com.project.ticket.domain.user.repository.UserRepository;
import com.project.ticket.global.exception.BusinessException;
import com.project.ticket.global.exception.ErrorCode;
import com.project.ticket.infra.payment.PaymentClient;
import java.time.Clock;
import java.util.List;
import java.util.ArrayList;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

@Component
public class TicketPurchaseProcessor {
    private final TicketRepository ticketRepository;
    private final UserRepository userRepository;
    private final PaymentClient paymentClient;
    private final Clock clock;

    public TicketPurchaseProcessor(TicketRepository tickets, UserRepository users, PaymentClient payment, Clock clock) {
        this.ticketRepository = tickets;
        this.userRepository = users;
        this.paymentClient = payment;
        this.clock = clock;
    }

    @Transactional
    public List<TicketPurchaseResponse> purchase(Long userId, List<Long> seatIds, String purchaseKey,
                                                String admissionId, SeatReader seatReader) {
        // Protect the cumulative limit even if the Redis claim expires during a slow transaction.
        var user = userRepository.findByIdForUpdate(userId).filter(found -> found.getDeletedAt() == null)
                .orElseThrow(() -> new BusinessException(ErrorCode.USER_NOT_FOUND));
        List<Seat> seats = seatIds.stream().sorted().map(id -> seatReader.find(id)
                .orElseThrow(() -> new BusinessException(ErrorCode.SEAT_NOT_FOUND))).toList();
        var concert = seats.getFirst().getConcert();
        if (seats.stream().anyMatch(seat -> !seat.getConcert().getId().equals(concert.getId()))) {
            throw new BusinessException(ErrorCode.INVALID_REQUEST);
        }
        if (ticketRepository.existsByUserIdAndSeatConcertIdAndAdmissionId(userId, concert.getId(), admissionId)) {
            throw new BusinessException(ErrorCode.ENTRY_TOKEN_INVALID);
        }
        if (ticketRepository.countByUserIdAndSeatConcertId(userId, concert.getId()) + seats.size() > 4) {
            throw new BusinessException(ErrorCode.PURCHASE_LIMIT_EXCEEDED);
        }
        if (!concert.isOnSale(clock.instant())) throw new BusinessException(ErrorCode.CONCERT_NOT_ON_SALE);
        if (seats.stream().anyMatch(seat -> seat.getStatus() != SeatStatus.AVAILABLE)) {
            throw new BusinessException(ErrorCode.SEAT_NOT_AVAILABLE);
        }
        long total = seats.stream().mapToLong(Seat::getPrice).sum();
        if (total > Integer.MAX_VALUE) throw new BusinessException(ErrorCode.INVALID_REQUEST);
        int amount = (int) total;
        // Explicit per-seat flush fixes SQL UPDATE order for overlapping optimistic batches too.
        for (Seat seat : seats) {
            seat.completeSale();
            ticketRepository.flush();
        }
        if (!paymentClient.pay(user, seats.getFirst(), amount).successful()) {
            throw new BusinessException(ErrorCode.PAYMENT_FAILED);
        }
        List<TicketPurchaseResponse> responses = new ArrayList<>();
        for (Seat seat : seats) {
            Ticket ticket = Ticket.complete(user, seat, seat.getPrice(), clock.instant());
            ticket.identifyPurchase(purchaseKey, admissionId);
            responses.add(TicketPurchaseResponse.from(ticketRepository.save(ticket)));
        }
        return List.copyOf(responses);
    }
}
