package com.project.ticket.infra.idempotency;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.project.ticket.domain.ticket.dto.TicketPurchaseResponse;
import com.project.ticket.support.InMemoryIdempotencyStore;
import java.time.Clock;
import java.time.Instant;
import org.junit.jupiter.api.Test;

class InMemoryIdempotencyStoreTest {
    @Test
    void expiredOwnerCannotCompleteOrReleaseNewOwnersMarker() {
        Clock clock = mock(Clock.class);
        Instant start = Instant.parse("2026-10-03T00:00:00Z");
        when(clock.instant()).thenReturn(start);
        var store = new InMemoryIdempotencyStore(clock);
        var response = java.util.List.of(new TicketPurchaseResponse(1L, 2L, 3L, 4));
        assertThat(store.tryAcquire("key", "hash", "old")).isEmpty();
        when(clock.instant()).thenReturn(start.plusSeconds(29));
        assertThat(store.tryAcquire("key", "hash", "other")).get()
                .extracting(IdempotencyStore.Entry::owner).isEqualTo("old");
        when(clock.instant()).thenReturn(start.plusSeconds(30));
        assertThat(store.tryAcquire("key", "hash", "new")).isEmpty();

        assertThat(store.complete("key", "hash", "old", response)).isFalse();
        assertThat(store.releaseIfOwner("key", "old")).isFalse();
        assertThat(store.complete("key", "different-hash", "new", response)).isFalse();
        assertThat(store.complete("key", "hash", "new", response)).isTrue();
        assertThat(store.releaseIfOwner("key", "new")).isFalse();
        assertThat(store.complete("key", "hash", "new", response)).isFalse();
        when(clock.instant()).thenReturn(start.plusSeconds(30).plus(IdempotencyStore.COMPLETED_TTL).minusNanos(1));
        assertThat(store.tryAcquire("key", "hash", "next")).get()
                .extracting(IdempotencyStore.Entry::response).isEqualTo(response);
        when(clock.instant()).thenReturn(start.plusSeconds(30).plus(IdempotencyStore.COMPLETED_TTL));
        assertThat(store.tryAcquire("key", "hash", "next")).isEmpty();
        assertThat(store.releaseIfOwner("key", "next")).isTrue();
        assertThat(store.tryAcquire("key", "hash", "last")).isEmpty();
    }
}
