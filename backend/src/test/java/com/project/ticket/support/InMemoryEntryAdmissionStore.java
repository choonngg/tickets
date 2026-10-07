package com.project.ticket.support;

import com.project.ticket.infra.queue.EntryAdmissionStore;
import com.project.ticket.global.exception.BusinessException;
import com.project.ticket.global.exception.ErrorCode;
import java.time.Clock;
import java.time.Instant;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Component;

@Component @Profile("test")
public class InMemoryEntryAdmissionStore implements EntryAdmissionStore {
    private static final Map<String, Entry> entries = new HashMap<>();
    private final Clock clock;
    public InMemoryEntryAdmissionStore(Clock clock) { this.clock = clock; }
    public static synchronized String grant(long concert, long user, Instant expiry) {
        String key = concert + ":" + user;
        Entry old = entries.get(key);
        if (old != null && old.expiry.equals(expiry)) return old.id;
        String id = UUID.randomUUID().toString();
        entries.put(key, new Entry(id, expiry));
        return id;
    }
    @Override public void claim(long concert, long user, String id, Instant expiry, String owner) {
        synchronized (InMemoryEntryAdmissionStore.class) {
            Entry entry = entries.get(concert + ":" + user);
            if (entry == null || !entry.id.equals(id) || !entry.expiry.isAfter(clock.instant())) {
                throw new BusinessException(ErrorCode.ENTRY_TOKEN_INVALID);
            }
            if (entry.owner != null) throw new BusinessException(ErrorCode.REQUEST_IN_PROGRESS);
            entry.owner = owner;
        }
    }
    @Override public void consume(long concert, long user, String id, String owner) {
        synchronized (InMemoryEntryAdmissionStore.class) {
            Entry entry = entries.get(concert + ":" + user);
            if (entry != null && entry.id.equals(id)) entries.remove(concert + ":" + user);
        }
    }
    @Override public void release(long concert, long user, String id, String owner) {
        synchronized (InMemoryEntryAdmissionStore.class) {
            Entry entry = entries.get(concert + ":" + user);
            if (entry != null && entry.id.equals(id) && owner.equals(entry.owner)) entry.owner = null;
        }
    }
    private static class Entry {
        final String id;
        final Instant expiry;
        String owner;
        Entry(String id, Instant expiry) { this.id = id; this.expiry = expiry; }
    }
}
