package com.project.ticket.infra.queue;

import java.time.Instant;

public interface EntryAdmissionStore {
    void claim(long concertId, long userId, String admissionId, Instant expiry, String owner);
    void consume(long concertId, long userId, String admissionId, String owner);
    void release(long concertId, long userId, String admissionId, String owner);
}
