package com.project.ticket.domain.queue.controller;

import com.project.ticket.domain.queue.dto.QueueEntryResponse;
import com.project.ticket.domain.queue.dto.QueueStatusResponse;
import com.project.ticket.domain.queue.service.EntryTokenProvider;
import com.project.ticket.domain.queue.service.QueueService;
import com.project.ticket.global.auth.AuthenticatedUser;
import java.util.Map;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/queue")
public class QueueController {
    private final QueueService queue;
    private final EntryTokenProvider tokens;

    public QueueController(QueueService queue, EntryTokenProvider tokens) {
        this.queue = queue;
        this.tokens = tokens;
    }

    @PostMapping("/{concertId}")
    public QueueStatusResponse register(@PathVariable long concertId, @AuthenticationPrincipal AuthenticatedUser user) {
        return queue.register(concertId, user.userId());
    }

    @GetMapping("/{concertId}/status")
    public QueueStatusResponse status(@PathVariable long concertId, @AuthenticationPrincipal AuthenticatedUser user) {
        return queue.status(concertId, user.userId());
    }

    @PostMapping("/{concertId}/enter")
    public QueueEntryResponse enter(@PathVariable long concertId, @AuthenticationPrincipal AuthenticatedUser user) {
        return queue.enter(concertId, user.userId());
    }

    @DeleteMapping("/{concertId}")
    public ResponseEntity<Void> cancel(@PathVariable long concertId, @AuthenticationPrincipal AuthenticatedUser user) {
        queue.cancel(concertId, user.userId());
        return ResponseEntity.noContent().build();
    }

    @GetMapping("/.well-known/jwks.json")
    public Map<String, Object> jwks() { return tokens.jwks(); }
}
