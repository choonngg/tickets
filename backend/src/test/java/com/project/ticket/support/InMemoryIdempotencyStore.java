package com.project.ticket.support;

import com.project.ticket.domain.ticket.dto.TicketPurchaseResponse;
import com.project.ticket.infra.idempotency.IdempotencyStore;
import java.time.Clock;
import java.time.Instant;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Component;

@Component
@Profile("test")
public class InMemoryIdempotencyStore implements IdempotencyStore {
    private final Map<String, ExpiringEntry> entries = new ConcurrentHashMap<>();
    private final Clock clock;

    public InMemoryIdempotencyStore(Clock clock) {
        this.clock = clock;
    }

    @Override
    public Optional<Entry> tryAcquire(String key, String requestHash, String owner) {
        Instant now = clock.instant();
        ExpiringEntry marker = new ExpiringEntry(new Entry("IN_PROGRESS", requestHash, owner, null),
                now.plus(IN_PROGRESS_TTL));
        ExpiringEntry result = entries.compute(key, (ignored, current) ->
                current == null || !current.expiresAt().isAfter(now) ? marker : current);
        return result == marker ? Optional.empty() : Optional.of(result.entry());
    }

    @Override
    public boolean complete(String key, String requestHash, String owner, java.util.List<TicketPurchaseResponse> response) {
        Instant now = clock.instant();
        ExpiringEntry completed = new ExpiringEntry(new Entry("COMPLETED", requestHash, owner, response),
                now.plus(COMPLETED_TTL));
        return entries.computeIfPresent(key, (ignored, current) -> {
            if (!current.expiresAt().isAfter(now)) return null;
            return ownedMarker(current, owner) && requestHash.equals(current.entry().requestHash())
                    ? completed : current;
        }) == completed;
    }

    @Override
    public boolean releaseIfOwner(String key, String owner) {
        boolean[] released = {false};
        Instant now = clock.instant();
        entries.computeIfPresent(key, (ignored, current) -> {
            if (!current.expiresAt().isAfter(now)) return null;
            if (ownedMarker(current, owner)) {
                released[0] = true;
                return null;
            }
            return current;
        });
        return released[0];
    }

    private boolean ownedMarker(ExpiringEntry current, String owner) {
        return "IN_PROGRESS".equals(current.entry().state()) && owner.equals(current.entry().owner());
    }

    private record ExpiringEntry(Entry entry, Instant expiresAt) { }
}
