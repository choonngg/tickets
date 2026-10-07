package com.project.ticket.infra.queue;

import com.project.ticket.global.exception.BusinessException;
import com.project.ticket.global.exception.ErrorCode;
import java.time.Clock;
import java.time.Instant;
import java.util.List;
import org.springframework.context.annotation.Profile;
import org.springframework.core.io.ClassPathResource;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.springframework.stereotype.Component;

@Component @Profile("!test")
public class RedisEntryAdmissionStore implements EntryAdmissionStore {
    private static final DefaultRedisScript<Long> SCRIPT = new DefaultRedisScript<>();
    static {
        SCRIPT.setLocation(new ClassPathResource("redis/queue/purchase.lua"));
        SCRIPT.setResultType(Long.class);
    }
    private final StringRedisTemplate redis;
    private final Clock clock;
    public RedisEntryAdmissionStore(StringRedisTemplate redis, Clock clock) {
        this.redis = redis;
        this.clock = clock;
    }
    @Override public void claim(long concert, long user, String admission, Instant expiry, String owner) {
        long result = execute("claim", concert, user, admission, owner, expiry.toEpochMilli());
        if (result == 2) throw new BusinessException(ErrorCode.REQUEST_IN_PROGRESS);
        if (result != 1) throw new BusinessException(ErrorCode.ENTRY_TOKEN_INVALID);
    }
    @Override public void consume(long concert, long user, String admission, String owner) {
        execute("consume", concert, user, admission, owner, 0);
    }
    @Override public void release(long concert, long user, String admission, String owner) {
        execute("release", concert, user, admission, owner, 0);
    }
    private long execute(String operation, long concert, long user, String admission, String owner, long expiry) {
        String prefix = "queue:" + concert + ":";
        Long result = redis.execute(SCRIPT, List.of(prefix + "active", prefix + "registered-at",
                prefix + "purchase:" + user), operation, Long.toString(user), admission, owner,
                Long.toString(expiry), Long.toString(clock.instant().toEpochMilli()));
        if (result == null) throw new IllegalStateException("Missing entry admission result");
        return result;
    }
}
