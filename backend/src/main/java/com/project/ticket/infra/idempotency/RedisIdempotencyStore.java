package com.project.ticket.infra.idempotency;

import com.project.ticket.domain.ticket.dto.TicketPurchaseResponse;
import java.util.List;
import java.util.Optional;
import org.springframework.context.annotation.Profile;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.springframework.stereotype.Component;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.json.JsonMapper;

@Component
@Profile("!test")
public class RedisIdempotencyStore implements IdempotencyStore {
    private static final DefaultRedisScript<String> ACQUIRE = new DefaultRedisScript<>("""
            while true do
                if redis.call('SET', KEYS[1], ARGV[1], 'NX', 'EX', ARGV[2]) then
                    return ''
                end
                local current = redis.call('GET', KEYS[1])
                if current then return current end
            end
            """, String.class);
    private static final DefaultRedisScript<Long> COMPLETE = new DefaultRedisScript<>("""
            local current = redis.call('GET', KEYS[1])
            if not current then return 0 end
            local entry = cjson.decode(current)
            if entry.owner ~= ARGV[1] or entry.state ~= 'IN_PROGRESS'
                or entry.requestHash ~= ARGV[2] then return 0 end
            redis.call('SET', KEYS[1], ARGV[3], 'EX', ARGV[4])
            return 1
            """, Long.class);
    private static final DefaultRedisScript<Long> RELEASE = new DefaultRedisScript<>("""
            local current = redis.call('GET', KEYS[1])
            if not current then return 0 end
            local entry = cjson.decode(current)
            if entry.owner ~= ARGV[1] or entry.state ~= 'IN_PROGRESS' then return 0 end
            return redis.call('DEL', KEYS[1])
            """, Long.class);

    private final StringRedisTemplate redisTemplate;
    private final JsonMapper jsonMapper;

    public RedisIdempotencyStore(StringRedisTemplate redisTemplate, JsonMapper jsonMapper) {
        this.redisTemplate = redisTemplate;
        this.jsonMapper = jsonMapper;
    }

    @Override
    public Optional<Entry> tryAcquire(String key, String requestHash, String owner) {
        String value = redisTemplate.execute(ACQUIRE, List.of(key),
                serialize(new Entry("IN_PROGRESS", requestHash, owner, null)),
                Long.toString(IN_PROGRESS_TTL.toSeconds()));
        if (value == null) {
            throw new IllegalStateException("Missing idempotency acquisition result.");
        }
        if (value.isEmpty()) {
            return Optional.empty();
        }
        try {
            return Optional.of(jsonMapper.readValue(value, Entry.class));
        } catch (JacksonException exception) {
            throw new IllegalStateException("Failed to read idempotency entry.", exception);
        }
    }

    @Override
    public boolean complete(String key, String requestHash, String owner, List<TicketPurchaseResponse> response) {
        return Long.valueOf(1).equals(redisTemplate.execute(COMPLETE, List.of(key), owner, requestHash,
                serialize(new Entry("COMPLETED", requestHash, owner, response)),
                Long.toString(COMPLETED_TTL.toSeconds())));
    }

    @Override
    public boolean releaseIfOwner(String key, String owner) {
        return Long.valueOf(1).equals(redisTemplate.execute(RELEASE, List.of(key), owner));
    }

    private String serialize(Entry entry) {
        try {
            return jsonMapper.writeValueAsString(entry);
        } catch (JacksonException exception) {
            throw new IllegalStateException("Failed to write idempotency entry.", exception);
        }
    }
}
