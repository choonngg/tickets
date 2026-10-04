package com.project.ticket.domain.concert.dto;

import jakarta.validation.constraints.AssertTrue;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.PositiveOrZero;
import jakarta.validation.constraints.Size;
import java.time.Instant;
import java.time.temporal.ChronoUnit;

public record ConcertCreateRequest(
        @NotBlank @Size(max = 100) String title,
        @NotBlank @Size(max = 255) String venue,
        @NotNull Instant concertDate,
        @NotNull Instant ticketOpenDate,
        @NotNull Instant ticketCloseDate,
        @PositiveOrZero int price
) {
    public ConcertCreateRequest {
        // DB는 DATETIME(6)이므로 검증 전에 마이크로초로 절삭해 검증·저장·응답 값을 일치시킨다.
        concertDate = micros(concertDate);
        ticketOpenDate = micros(ticketOpenDate);
        ticketCloseDate = micros(ticketCloseDate);
    }

    private static Instant micros(Instant value) {
        return value == null ? null : value.truncatedTo(ChronoUnit.MICROS);
    }

    @AssertTrue(message = "Ticket dates must satisfy open < close <= concert date.")
    public boolean isTicketWindowValid() {
        return ticketOpenDate == null || ticketCloseDate == null || concertDate == null
                || (ticketOpenDate.isBefore(ticketCloseDate) && !ticketCloseDate.isAfter(concertDate));
    }
}
