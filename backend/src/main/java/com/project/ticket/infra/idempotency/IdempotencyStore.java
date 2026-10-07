package com.project.ticket.infra.idempotency;

import com.project.ticket.domain.ticket.dto.TicketPurchaseResponse;
import java.util.List;
import java.time.Duration;
import java.util.Optional;

public interface IdempotencyStore {
    Duration IN_PROGRESS_TTL = Duration.ofSeconds(30);
    Duration COMPLETED_TTL = Duration.ofHours(24);

    // Empty means this owner acquired the key; otherwise return the existing entry atomically.
    Optional<Entry> tryAcquire(String key, String requestHash, String owner);

    boolean complete(String key, String requestHash, String owner, List<TicketPurchaseResponse> response);

    boolean releaseIfOwner(String key, String owner);

    record Entry(String state, String requestHash, String owner, List<TicketPurchaseResponse> response) { }
}
