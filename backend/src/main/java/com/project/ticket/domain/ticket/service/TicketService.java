package com.project.ticket.domain.ticket.service;

import com.project.ticket.domain.ticket.dto.TicketPurchaseRequest;
import com.project.ticket.domain.ticket.dto.TicketPurchaseResponse;
import com.project.ticket.domain.ticket.dto.TicketViewResponse;
import com.project.ticket.domain.ticket.repository.TicketRepository;
import com.project.ticket.domain.ticket.service.purchase.TicketPurchaseStrategyRouter;
import com.project.ticket.global.exception.BusinessException;
import com.project.ticket.global.exception.ErrorCode;
import com.project.ticket.infra.idempotency.IdempotencyService;
import java.util.List;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;

@Service
public class TicketService {
    private static final Logger log = LoggerFactory.getLogger(TicketService.class);
    private final IdempotencyService idempotencyService;
    private final TicketPurchaseStrategyRouter strategyRouter;
    private final TicketRepository ticketRepository;

    public TicketService(
            IdempotencyService idempotencyService,
            TicketPurchaseStrategyRouter strategyRouter,
            TicketRepository ticketRepository
    ) {
        this.idempotencyService = idempotencyService;
        this.strategyRouter = strategyRouter;
        this.ticketRepository = ticketRepository;
    }

    public TicketPurchaseResponse purchase(
            Long userId,
            TicketPurchaseRequest request,
            String idempotencyKey
    ) {
        if (!StringUtils.hasText(idempotencyKey)) {
            throw new BusinessException(ErrorCode.IDEMPOTENCY_KEY_REQUIRED);
        }
        String requestHash = idempotencyService.requestHash(request);
        String owner = UUID.randomUUID().toString();
        return idempotencyService.tryAcquire(userId, idempotencyKey, requestHash, owner)
                .orElseGet(() -> purchaseNew(userId, request, idempotencyKey, requestHash, owner));
    }

    private TicketPurchaseResponse purchaseNew(Long userId, TicketPurchaseRequest request, String idempotencyKey,
                                              String requestHash, String owner) {
        TicketPurchaseResponse response;
        try {
            response = ticketRepository.findBySeatId(request.seatId())
                    .map(ticket -> {
                        if (!ticket.getUser().getId().equals(userId)) {
                            throw new BusinessException(ErrorCode.SEAT_NOT_AVAILABLE);
                        }
                        return TicketPurchaseResponse.from(ticket);
                    })
                    .orElseGet(() -> strategyRouter.current().purchase(userId, request.seatId()));
        } catch (RuntimeException exception) {
            try {
                idempotencyService.releaseIfOwner(userId, idempotencyKey, owner);
            } catch (RuntimeException releaseException) {
                log.warn("Failed to release idempotency marker for user {} and seat {}",
                        userId, request.seatId(), releaseException);
            }
            throw exception;
        }
        try {
            if (!idempotencyService.complete(userId, idempotencyKey, requestHash, owner, response)) {
                log.warn("Idempotency completion lost ownership for user {} and seat {}", userId, request.seatId());
            }
        } catch (RuntimeException exception) {
            log.warn("Failed to store committed purchase response for user {} and seat {}",
                    userId, request.seatId(), exception);
        }
        return response;
    }

    @Transactional(readOnly = true)
    public List<TicketViewResponse> findMyTickets(Long userId) {
        return ticketRepository.findByUserIdOrderByCreatedAtDesc(userId).stream()
                .map(TicketViewResponse::from)
                .toList();
    }
}
