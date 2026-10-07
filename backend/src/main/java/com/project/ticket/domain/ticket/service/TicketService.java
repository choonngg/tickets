package com.project.ticket.domain.ticket.service;

import com.project.ticket.domain.ticket.dto.TicketPurchaseRequest;
import com.project.ticket.domain.ticket.dto.TicketPurchaseResponse;
import com.project.ticket.domain.ticket.dto.TicketViewResponse;
import com.project.ticket.domain.ticket.repository.TicketRepository;
import com.project.ticket.domain.ticket.service.purchase.TicketPurchaseStrategyRouter;
import com.project.ticket.global.exception.BusinessException;
import com.project.ticket.global.exception.ErrorCode;
import com.project.ticket.infra.idempotency.IdempotencyService;
import com.project.ticket.infra.queue.EntryAdmissionStore;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
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
    private final EntryTokenValidator entryTokenValidator;
    private final EntryAdmissionStore admissions;

    public TicketService(IdempotencyService idempotencyService, TicketPurchaseStrategyRouter strategyRouter,
            TicketRepository ticketRepository, EntryTokenValidator entryTokenValidator, EntryAdmissionStore admissions) {
        this.idempotencyService = idempotencyService;
        this.strategyRouter = strategyRouter;
        this.ticketRepository = ticketRepository;
        this.entryTokenValidator = entryTokenValidator;
        this.admissions = admissions;
    }

    public List<TicketPurchaseResponse> purchase(Long userId, TicketPurchaseRequest request,
                                                String idempotencyKey, String entryToken) {
        List<Long> ids = request.sortedSeatIds();
        if (!StringUtils.hasText(idempotencyKey)) {
            // Preserve the missing entry-header error for direct, unauthorised booking attempts.
            entryTokenValidator.validate(entryToken, userId, ids);
            throw new BusinessException(ErrorCode.IDEMPOTENCY_KEY_REQUIRED);
        }
        String requestHash = idempotencyService.requestHash(request);
        String owner = UUID.randomUUID().toString();
        // Completed replay precedes entry validation: the successful token has already been consumed.
        var cached = idempotencyService.tryAcquire(userId, idempotencyKey, requestHash, owner);
        if (cached.isPresent()) return cached.get();
        try {
            String purchaseKey = purchaseKey(idempotencyKey);
            var recovered = ticketRepository.findByUserIdAndPurchaseKeyOrderBySeatIdAsc(userId, purchaseKey);
            List<TicketPurchaseResponse> response;
            if (!recovered.isEmpty()) {
                if (!recovered.stream().map(ticket -> ticket.getSeat().getId()).toList().equals(ids)) {
                    throw new BusinessException(ErrorCode.IDEMPOTENCY_CONFLICT);
                }
                var ticket = recovered.getFirst();
                cleanup(() -> admissions.consume(ticket.getSeat().getConcert().getId(), userId,
                        ticket.getAdmissionId(), owner));
                response = recovered.stream().map(TicketPurchaseResponse::from).toList();
            } else {
                var admission = entryTokenValidator.validate(entryToken, userId, ids);
                try {
                    // claim may have been applied in Redis even if the call throws (e.g. response timeout).
                    // release only deletes when the stored owner matches, so another owner's claim is untouched.
                    admissions.claim(admission.concertId(), userId, admission.admissionId(), admission.expiry(), owner);
                    response = strategyRouter.current().purchase(userId, ids, purchaseKey, admission.admissionId());
                } catch (RuntimeException exception) {
                    cleanup(() -> admissions.release(admission.concertId(), userId, admission.admissionId(), owner));
                    throw exception;
                }
                // The strategy's transaction has committed. Cleanup errors must not turn success into failure.
                cleanup(() -> admissions.consume(admission.concertId(), userId, admission.admissionId(), owner));
            }
            try {
                if (!idempotencyService.complete(userId, idempotencyKey, requestHash, owner, response)) {
                    log.warn("Idempotency completion lost ownership for user {}", userId);
                }
            } catch (RuntimeException exception) {
                log.warn("Failed to store committed purchase response for user {}", userId, exception);
            }
            return response;
        } catch (RuntimeException exception) {
            cleanup(() -> idempotencyService.releaseIfOwner(userId, idempotencyKey, owner));
            throw exception;
        }
    }

    private void cleanup(Runnable action) {
        try { action.run(); }
        catch (RuntimeException exception) { log.warn("Failed to clean up purchase Redis state", exception); }
    }

    private String purchaseKey(String key) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                    .digest(key.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException exception) { throw new IllegalStateException(exception); }
    }

    @Transactional(readOnly = true)
    public List<TicketViewResponse> findMyTickets(Long userId) {
        return ticketRepository.findByUserIdOrderByCreatedAtDesc(userId).stream().map(TicketViewResponse::from).toList();
    }
}
