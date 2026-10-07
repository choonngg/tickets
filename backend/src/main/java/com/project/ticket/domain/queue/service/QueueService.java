package com.project.ticket.domain.queue.service;

import com.project.ticket.domain.concert.repository.ConcertRepository;
import com.project.ticket.domain.queue.dto.QueueEntryResponse;
import com.project.ticket.domain.queue.dto.QueueStatusResponse;
import com.project.ticket.domain.user.repository.UserRepository;
import com.project.ticket.global.exception.BusinessException;
import com.project.ticket.global.exception.ErrorCode;
import com.project.ticket.infra.queue.QueueRedisRepository;
import com.project.ticket.infra.queue.QueueRedisRepository.State;
import java.time.Clock;
import org.springframework.stereotype.Service;

@Service
public class QueueService {
    private final QueueRedisRepository queue;
    private final ConcertRepository concerts;
    private final UserRepository users;
    private final EntryTokenProvider tokens;
    private final Clock clock;

    public QueueService(QueueRedisRepository queue, ConcertRepository concerts, UserRepository users,
            EntryTokenProvider tokens, Clock clock) {
        this.queue = queue;
        this.concerts = concerts;
        this.users = users;
        this.tokens = tokens;
        this.clock = clock;
    }

    public QueueStatusResponse register(long concertId, long userId) {
        requireUser(userId);
        var concert = concerts.findById(concertId)
                .orElseThrow(() -> new BusinessException(ErrorCode.CONCERT_NOT_FOUND));
        if (!concert.isOnSale(clock.instant())) throw new BusinessException(ErrorCode.CONCERT_NOT_ON_SALE);
        return QueueStatusResponse.from(concertId, queue.register(concertId, userId), clock.instant());
    }

    public QueueStatusResponse status(long concertId, long userId) {
        return QueueStatusResponse.from(concertId, queue.status(concertId, userId), clock.instant());
    }

    public QueueEntryResponse enter(long concertId, long userId) {
        requireUser(userId);
        requireConcert(concertId);
        var result = queue.enter(concertId, userId);
        if (result.state() == State.EXPIRED) throw new BusinessException(ErrorCode.QUEUE_ADMISSION_EXPIRED);
        if (result.state() != State.ACTIVE) throw new BusinessException(ErrorCode.QUEUE_NOT_ADMITTED);
        // Mint on every successful call, including ACTIVE retries, using the unchanged Lua score.
        return new QueueEntryResponse(concertId, State.ACTIVE,
                tokens.issue(concertId, userId, result.expiresAt(), result.admissionId()), result.expiresAt(), clock.instant());
    }

    public void cancel(long concertId, long userId) {
        requireUser(userId);
        requireConcert(concertId);
        queue.cancel(concertId, userId);
    }

    private void requireUser(long userId) {
        users.findById(userId).filter(user -> user.getDeletedAt() == null)
                .orElseThrow(() -> new BusinessException(ErrorCode.USER_NOT_FOUND));
    }

    private void requireConcert(long concertId) {
        if (!concerts.existsById(concertId)) throw new BusinessException(ErrorCode.CONCERT_NOT_FOUND);
    }
}
