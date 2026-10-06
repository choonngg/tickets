package com.project.ticket.domain.queue.service;

import static org.mockito.Mockito.*;
import com.project.ticket.infra.queue.QueueRedisRepository;
import java.util.LinkedHashSet;
import java.util.List;
import org.junit.jupiter.api.Test;

class QueueAdmissionWorkerTest {
    @Test void isolatesPerformanceFailureAndCallsEachPerformanceOnlyOnce() {
        var queue = mock(QueueRedisRepository.class);
        when(queue.activePerformances()).thenReturn(new LinkedHashSet<>(List.of("123", "invalid", "456")));
        when(queue.admit(123)).thenThrow(new IllegalStateException("Injected Lua error"));
        when(queue.admit(456)).thenReturn(new QueueRedisRepository.Admission(2, 0));
        new QueueAdmissionWorker(queue).runCycle();
        var ordered = inOrder(queue);
        ordered.verify(queue).activePerformances();
        ordered.verify(queue).admit(123);
        ordered.verify(queue).admit(456);
        verifyNoMoreInteractions(queue);
    }

    @Test void activeListFailureDoesNotEscapeScheduledCycle() {
        var queue = mock(QueueRedisRepository.class);
        when(queue.activePerformances()).thenThrow(new IllegalStateException("Redis unavailable"));
        new QueueAdmissionWorker(queue).runCycle();
        verify(queue).activePerformances();
        verifyNoMoreInteractions(queue);
    }
}
