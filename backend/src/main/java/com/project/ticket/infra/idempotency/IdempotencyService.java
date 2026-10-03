package com.project.ticket.infra.idempotency;

import com.project.ticket.domain.ticket.dto.TicketPurchaseRequest;
import com.project.ticket.domain.ticket.dto.TicketPurchaseResponse;
import com.project.ticket.global.exception.BusinessException;
import com.project.ticket.global.exception.ErrorCode;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.Optional;
import org.springframework.stereotype.Service;

@Service
public class IdempotencyService {
    private final IdempotencyStore idempotencyStore;

    public IdempotencyService(IdempotencyStore idempotencyStore) {
        this.idempotencyStore = idempotencyStore;
    }

    public Optional<TicketPurchaseResponse> tryAcquire(
            Long userId, String idempotencyKey, String requestHash, String owner
    ) {
        return idempotencyStore.tryAcquire(ticketPurchaseKey(userId, idempotencyKey), requestHash, owner)
                .map(entry -> {
                    if (!requestHash.equals(entry.requestHash())) {
                        throw new BusinessException(ErrorCode.IDEMPOTENCY_CONFLICT);
                    }
                    if ("IN_PROGRESS".equals(entry.state())) {
                        throw new BusinessException(ErrorCode.REQUEST_IN_PROGRESS);
                    }
                    return entry.response();
                });
    }

    public boolean complete(Long userId, String idempotencyKey, String requestHash, String owner,
                         TicketPurchaseResponse response) {
        return idempotencyStore.complete(ticketPurchaseKey(userId, idempotencyKey), requestHash, owner, response);
    }

    public void releaseIfOwner(Long userId, String idempotencyKey, String owner) {
        idempotencyStore.releaseIfOwner(ticketPurchaseKey(userId, idempotencyKey), owner);
    }

    public String requestHash(TicketPurchaseRequest request) {
        // Canonical body: seatId is currently the purchase request's only field.
        String body = "{\"seatId\":" + request.seatId() + "}";
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                    .digest(body.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException("SHA-256 is unavailable.", exception);
        }
    }

    private String ticketPurchaseKey(Long userId, String idempotencyKey) {
        return "idempotency:tickets:" + userId + ":" + idempotencyKey;
    }
}
