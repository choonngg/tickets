package com.project.ticket.infra.lock;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.redisson.api.RLock;
import org.redisson.api.RedissonClient;

class RedissonSeatLockManagerTest {
    private RLock lock;
    private RedissonSeatLockManager manager;

    @BeforeEach
    void setUp() throws InterruptedException {
        RedissonClient client = mock(RedissonClient.class);
        lock = mock(RLock.class);
        when(client.getLock("lock:seat:1")).thenReturn(lock);
        when(lock.tryLock(anyLong(), anyLong(), eq(TimeUnit.SECONDS))).thenReturn(true);
        when(lock.isHeldByCurrentThread()).thenReturn(true);
        doThrow(new IllegalMonitorStateException("lease expired")).when(lock).unlock();
        manager = new RedissonSeatLockManager(client);
    }

    @Test
    void preservesResultWhenUnlockFails() {
        String result = manager.executeWithLock(1L, () -> "done");

        assertThat(result).isEqualTo("done");
        verify(lock).unlock();
    }

    @Test
    void preservesOriginalExceptionWhenUnlockFails() {
        IllegalStateException original = new IllegalStateException("original");

        assertThatThrownBy(() -> manager.executeWithLock(1L, () -> {
            throw original;
        })).isSameAs(original);
        verify(lock).unlock();
    }
}
