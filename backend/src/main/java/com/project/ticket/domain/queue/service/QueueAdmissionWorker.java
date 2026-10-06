package com.project.ticket.domain.queue.service;

import com.project.ticket.infra.queue.QueueRedisRepository;
import java.util.Set;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

@Component
public class QueueAdmissionWorker {
    private static final Logger log = LoggerFactory.getLogger(QueueAdmissionWorker.class);
    private final QueueRedisRepository queue;

    public QueueAdmissionWorker(QueueRedisRepository queue) { this.queue = queue; }

    @Scheduled(fixedDelayString = "${ticket.queue.worker-delay-ms:5000}",
            initialDelayString = "${ticket.queue.worker-initial-delay-ms:5000}")
    public void runCycle() {
        Set<String> performances;
        try {
            performances = queue.activePerformances();
        } catch (RuntimeException exception) {
            log.error("Failed to list active queue performances", exception);
            return;
        }
        // Lua serializes cleanup/capacity/admission/history, so concurrent instances cannot
        // exceed capacity or admit the same member twice. Multiple schedulers would still
        // multiply cycles and skew the 12-cycle rate: deployment assumes ONE worker (design 3-1).
        for (String performance : performances) {
            try {
                queue.admit(Long.parseLong(performance));
            } catch (RuntimeException exception) {
                log.error("Queue admission failed for concert {}", performance, exception);
            }
        }
    }
}
