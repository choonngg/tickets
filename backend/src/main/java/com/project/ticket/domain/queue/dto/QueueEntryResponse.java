package com.project.ticket.domain.queue.dto;

import com.project.ticket.infra.queue.QueueRedisRepository.State;
import java.time.Instant;

public record QueueEntryResponse(long concertId, State status, String entryToken, Instant expiresAt, Instant serverTime) { }
