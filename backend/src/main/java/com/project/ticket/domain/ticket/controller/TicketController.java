package com.project.ticket.domain.ticket.controller;

import com.project.ticket.domain.ticket.dto.TicketPurchaseRequest;
import com.project.ticket.domain.ticket.dto.TicketPurchaseResponse;
import com.project.ticket.domain.ticket.dto.TicketViewResponse;
import com.project.ticket.domain.ticket.service.TicketService;
import com.project.ticket.global.auth.AuthenticatedUser;
import jakarta.validation.Valid;
import java.util.List;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/tickets")
public class TicketController {
    private final TicketService ticketService;

    public TicketController(TicketService ticketService) {
        this.ticketService = ticketService;
    }

    @PostMapping
    public ResponseEntity<List<TicketPurchaseResponse>> purchase(
            @AuthenticationPrincipal AuthenticatedUser user,
            @Valid @RequestBody TicketPurchaseRequest request,
            @RequestHeader(value = "Idempotency-Key", required = false) String idempotencyKey,
            @RequestHeader(value = "X-Entry-Token", required = false) String entryToken
    ) {
        return ResponseEntity.status(HttpStatus.CREATED)
                .body(ticketService.purchase(user.userId(), request, idempotencyKey, entryToken));
    }

    @GetMapping("/me")
    public List<TicketViewResponse> findMyTickets(@AuthenticationPrincipal AuthenticatedUser user) {
        return ticketService.findMyTickets(user.userId());
    }
}
