package com.project.ticket.infra.lock;

import com.project.ticket.global.exception.BusinessException;
import com.project.ticket.global.exception.ErrorCode;
import java.util.concurrent.TimeUnit;
import java.util.function.Supplier;
import org.redisson.api.RLock;
import org.redisson.api.RedissonClient;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Component;

@Component
@Profile("!test")
public class RedissonSeatLockManager implements SeatLockManager {
    private static final Logger log = LoggerFactory.getLogger(RedissonSeatLockManager.class);

    private final RedissonClient redissonClient;

    public RedissonSeatLockManager(RedissonClient redissonClient) {
        this.redissonClient = redissonClient;
    }

    @Override
    public <T> T executeWithLock(Long seatId, Supplier<T> supplier) {
        RLock lock = redissonClient.getLock("lock:seat:" + seatId);
        boolean locked;
        try {
            locked = lock.tryLock(0, 10, TimeUnit.SECONDS);
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            throw new BusinessException(ErrorCode.SEAT_LOCK_FAILED);
        }
        if (!locked) {
            throw new BusinessException(ErrorCode.SEAT_LOCK_FAILED);
        }
        try {
            return supplier.get();
        } finally {
            unlockQuietly(lock, seatId);
        }
    }

    private void unlockQuietly(RLock lock, Long seatId) {
        try {
            if (lock.isHeldByCurrentThread()) {
                lock.unlock();
            }
        } catch (RuntimeException exception) {
            // unlock 실패가 완료된 결과나 원래 예외를 덮어쓰지 않도록 로그만 남긴다. 락은 leaseTime(10초)으로 만료된다.
            log.warn("Failed to unlock seat lock. seatId={}", seatId, exception);
        }
    }
}
