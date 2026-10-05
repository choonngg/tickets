package com.project.ticket.domain.auth.service;

import com.project.ticket.domain.auth.repository.AuthSessionRepository;
import java.time.Clock;
import java.time.temporal.ChronoUnit;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class SessionCleanup {
    private final AuthSessionRepository sessions;
    private final Clock clock;

    public SessionCleanup(AuthSessionRepository sessions, Clock clock) {
        this.sessions = sessions;
        this.clock = clock;
    }

    @Scheduled(cron = "0 0 3 * * *", zone = "UTC")
    @Transactional
    public void clean() {
        // Delete Session first; its FK cascades all RT history, using the same lock order as refresh.
        sessions.deleteExpiredBefore(clock.instant().minus(7, ChronoUnit.DAYS));
    }
}
