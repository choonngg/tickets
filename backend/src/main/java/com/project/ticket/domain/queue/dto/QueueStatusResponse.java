package com.project.ticket.domain.queue.dto;

import com.project.ticket.infra.queue.QueueRedisRepository;
import java.time.Instant;

public record QueueStatusResponse(long concertId, QueueRedisRepository.State status, long rank, long behind,
        long nextPollAfterMs, Double estimatedWaitSeconds, Instant expiresAt, Instant serverTime) {
    public static QueueStatusResponse from(long concertId, QueueRedisRepository.Status status, Instant serverTime) {
        Double estimate = status.state() == QueueRedisRepository.State.WAITING && status.admitRate() > 0
                ? status.rank() * 60.0 / status.admitRate() : null;
        return new QueueStatusResponse(concertId, status.state(), status.rank(), status.behind(),
                status.nextPollAfterMs(), estimate, status.expiresAt(), serverTime);
    }
}
