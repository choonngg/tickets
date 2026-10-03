package com.project.ticket;

import static org.assertj.core.api.Assertions.assertThat;

import com.project.ticket.support.IntegrationTestSupport;
import java.time.Duration;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.redis.core.StringRedisTemplate;

class RedisIntegrationTest extends IntegrationTestSupport {
    @Autowired
    StringRedisTemplate redis;

    @Test
    void writesAndReadsValueFromRedis() {
        String key = "integration:smoke:" + UUID.randomUUID();
        try {
            redis.opsForValue().set(key, "ticket", Duration.ofMinutes(1));

            assertThat(redis.opsForValue().get(key)).isEqualTo("ticket");
        } finally {
            redis.delete(key);
        }
    }
}
