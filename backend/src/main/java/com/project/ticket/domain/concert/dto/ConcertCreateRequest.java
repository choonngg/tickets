package com.project.ticket.domain.concert.dto;

import jakarta.validation.constraints.AssertTrue;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.PositiveOrZero;
import jakarta.validation.constraints.Size;
import java.time.LocalDateTime;

public record ConcertCreateRequest(
        @NotBlank @Size(max = 100) String title,
        @NotBlank @Size(max = 255) String venue,
        @NotNull LocalDateTime concertDate,
        @NotNull LocalDateTime ticketOpenDate,
        @NotNull LocalDateTime ticketCloseDate,
        @PositiveOrZero int price
) {
    @AssertTrue(message = "Ticket dates must satisfy open < close <= concert date.")
    public boolean isTicketWindowValid() {
        return ticketOpenDate == null || ticketCloseDate == null || concertDate == null
                || (ticketOpenDate.isBefore(ticketCloseDate) && !ticketCloseDate.isAfter(concertDate));
    }
}
