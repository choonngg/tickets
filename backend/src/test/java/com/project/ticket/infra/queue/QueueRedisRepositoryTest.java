package com.project.ticket.infra.queue;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

import com.project.ticket.global.exception.BusinessException;
import com.project.ticket.global.exception.ErrorCode;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.context.annotation.Configuration;
import org.springframework.data.redis.core.StringRedisTemplate;

class QueueRedisRepositoryTest {
    @Test void usesInjectedClockAndKeepsJitterOutsideLuaTtl() {
        var redis = mock(StringRedisTemplate.class);
        Instant now = Instant.parse("2026-10-05T00:00:00Z");
        when(redis.execute(eq(QueueScripts.script(QueueScripts.Operation.STATUS)), anyList(), any(String[].class)))
                .thenReturn(List.of("WAITING", 100L, 2L, now.plusSeconds(9).toEpochMilli(), 3000L, 120L));
        var queue = new QueueRedisRepository(redis, QueueProperties.defaults(), Clock.fixed(now, ZoneOffset.UTC));
        var status = queue.status(123, 9_007_199_254_740_993L);
        var arguments = ArgumentCaptor.forClass(String[].class);
        verify(redis).execute(eq(QueueScripts.script(QueueScripts.Operation.STATUS)),
                eq(QueueScripts.keys(123)), arguments.capture());
        assertThat(arguments.getValue()[2]).isEqualTo(Long.toString(now.toEpochMilli()));
        assertThat(arguments.getValue()[1]).isEqualTo("9007199254740993");
        assertThat(status.expiresAt()).isEqualTo(now.plusSeconds(9));
        assertThat(status.nextPollAfterMs()).isBetween(2400L, 3600L);
        assertThat(status.rank()).isEqualTo(100);
        assertThat(status.admitRate()).isEqualTo(120);
    }

    @Test void activeCancellationMapsLuaResultToConflictWithOneRedisCall() {
        var redis = mock(StringRedisTemplate.class);
        var queue = new QueueRedisRepository(redis, QueueProperties.defaults(), Clock.systemUTC());
        when(redis.execute(eq(QueueScripts.script(QueueScripts.Operation.CANCEL)), anyList(), any(String[].class)))
                .thenReturn(List.of("ACTIVE", 0L, 0L, 1L, 0L, 0L));
        assertThatThrownBy(() -> queue.cancel(123, 1)).isInstanceOfSatisfying(BusinessException.class,
                error -> assertThat(error.errorCode()).isEqualTo(ErrorCode.QUEUE_ALREADY_ACTIVE));
        verify(redis).execute(eq(QueueScripts.script(QueueScripts.Operation.CANCEL)),
                eq(QueueScripts.keys(123)), any(String[].class));
        verifyNoMoreInteractions(redis);
    }

    @Test void bindsDefaultsAndExternalOverridesAndRejectsInvalidSettings() {
        var runner = new ApplicationContextRunner().withUserConfiguration(PropertiesConfiguration.class);
        runner.run(context -> {
            assertThat(context).hasNotFailed();
            assertThat(context.getBean(QueueProperties.class)).isEqualTo(QueueProperties.defaults());
        });
        runner.withPropertyValues("ticket.queue.max-concurrent=25", "ticket.queue.fast-poll-ms=4000")
                .run(context -> {
                    assertThat(context).hasNotFailed();
                    assertThat(context.getBean(QueueProperties.class).maxConcurrent()).isEqualTo(25);
                    assertThat(context.getBean(QueueProperties.class).fastPollMs()).isEqualTo(4000);
                });
        for (String setting : List.of("max-concurrent=0", "cleanup-batch-size=201", "admitted-ttl-ms=0",
                "fast-rank-threshold=1001", "slow-poll-ms=1000")) {
            runner.withPropertyValues("ticket.queue." + setting).run(context -> assertThat(context).hasFailed());
        }
    }

    @Test void rejectsInvalidIdsBeforeRedisAndDoesNotConvertMissingResultsToSuccess() {
        var redis = mock(StringRedisTemplate.class);
        var queue = new QueueRedisRepository(redis, QueueProperties.defaults(), Clock.systemUTC());
        assertThatThrownBy(() -> queue.register(0, 1)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> queue.status(1, 0)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> queue.enter(1, -1)).isInstanceOf(IllegalArgumentException.class);
        verifyNoInteractions(redis);
        assertThatThrownBy(() -> queue.admit(1)).isInstanceOf(IllegalStateException.class);
    }

    @Configuration(proxyBeanMethods = false)
    @EnableConfigurationProperties(QueueProperties.class)
    static class PropertiesConfiguration { }
}
