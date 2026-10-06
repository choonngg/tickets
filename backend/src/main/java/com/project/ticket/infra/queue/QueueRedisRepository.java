package com.project.ticket.infra.queue;

import com.project.ticket.global.exception.BusinessException;
import com.project.ticket.global.exception.ErrorCode;
import java.time.Clock;
import java.time.Instant;
import java.util.List;
import java.util.Set;
import java.util.concurrent.ThreadLocalRandom;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Repository;

@Repository
@EnableConfigurationProperties(QueueProperties.class)
public class QueueRedisRepository {
    public enum State { WAITING, ADMITTED, ACTIVE, EXPIRED, NOT_ADMITTED }
    /** Non-WAITING results have rank/behind/poll = 0. EXPIRED/NOT_ADMITTED have no expiry. */
    public record Status(State state, long rank, long behind, Instant expiresAt,
                         long basePollAfterMs, long nextPollAfterMs, long admitRate) { }
    public record Admission(int admitted, int cleaned) { }

    private final StringRedisTemplate redis;
    private final QueueProperties properties;
    private final Clock clock;

    public QueueRedisRepository(StringRedisTemplate redis, QueueProperties properties, Clock clock) {
        this.redis = redis;
        this.properties = properties;
        this.clock = clock;
    }

    public Status register(long performanceId, long userId) {
        return statusResult(execute(QueueScripts.Operation.REGISTER, performanceId, userId));
    }

    public Status status(long performanceId, long userId) {
        return statusResult(execute(QueueScripts.Operation.STATUS, performanceId, userId));
    }

    /** One worker cycle: shared cleanup budget, capacity check, bounded admission and history update. */
    public Admission admit(long performanceId) {
        List<?> result = execute(QueueScripts.Operation.ADMIT, performanceId, null);
        return new Admission(Math.toIntExact(number(result.get(0))), Math.toIntExact(number(result.get(1))));
    }

    /** Entry retries preserve the original ACTIVE expiry; this layer does not issue tokens. */
    public Status enter(long performanceId, long userId) {
        return statusResult(execute(QueueScripts.Operation.ENTER, performanceId, userId));
    }

    /** Rejects valid ACTIVE without writes; otherwise removes state and checks emptiness atomically. */
    public void cancel(long performanceId, long userId) {
        List<?> result = execute(QueueScripts.Operation.CANCEL, performanceId, userId);
        if (State.ACTIVE.name().equals(result.get(0).toString())) {
            throw new BusinessException(ErrorCode.QUEUE_ALREADY_ACTIVE);
        }
    }

    public Set<String> activePerformances() {
        Set<String> result = redis.opsForSet().members(QueueScripts.ACTIVE_PERFORMANCES);
        if (result == null) throw new IllegalStateException("Missing active performances result.");
        return Set.copyOf(result);
    }

    private List<?> execute(QueueScripts.Operation operation, long performanceId, Long userId) {
        List<?> result = redis.execute(QueueScripts.script(operation), QueueScripts.keys(performanceId),
                arguments(performanceId, userId));
        if (result == null) throw new IllegalStateException("Missing queue Lua result.");
        return result;
    }

    // ARGV order is documented in QUEUE_REDIS.md and common.lua. IDs remain strings in Lua.
    String[] arguments(long performanceId, Long userId) {
        if (performanceId <= 0 || (userId != null && userId <= 0)) {
            throw new IllegalArgumentException("Queue IDs must be positive.");
        }
        return new String[]{Long.toString(performanceId), userId == null ? "" : userId.toString(),
                Long.toString(clock.instant().toEpochMilli()), Integer.toString(properties.maxConcurrent()),
                Integer.toString(properties.admitPerCycle()), Long.toString(properties.admittedTtlMs()),
                Long.toString(properties.activeTtlMs()), Integer.toString(properties.cleanupBatchSize()),
                Long.toString(properties.reregisterGraceMs()), Integer.toString(properties.fastRankThreshold()),
                Integer.toString(properties.mediumRankThreshold()), Long.toString(properties.fastPollMs()),
                Long.toString(properties.mediumPollMs()), Long.toString(properties.slowPollMs())};
    }

    private Status statusResult(List<?> result) {
        long basePoll = number(result.get(4));
        long jittered = basePoll == 0 ? 0 : Math.round(basePoll * ThreadLocalRandom.current().nextDouble(0.8, 1.2));
        long expiry = number(result.get(3));
        return new Status(State.valueOf(result.get(0).toString()), number(result.get(1)), number(result.get(2)),
                expiry == 0 ? null : Instant.ofEpochMilli(expiry), basePoll, jittered, number(result.get(5)));
    }

    private static long number(Object value) { return Long.parseLong(value.toString()); }
}
