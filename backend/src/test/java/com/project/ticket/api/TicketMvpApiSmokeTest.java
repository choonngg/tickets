package com.project.ticket.api;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.time.Clock;
import java.time.temporal.ChronoUnit;
import java.util.Map;
import com.project.ticket.support.TestUsers;
import com.project.ticket.support.TestEntryTokens;
import com.project.ticket.domain.seat.repository.SeatRepository;
import com.project.ticket.domain.user.entity.User;
import com.project.ticket.domain.user.repository.UserRepository;
import com.project.ticket.domain.auth.entity.AuthSession;
import com.project.ticket.domain.auth.repository.AuthSessionRepository;
import com.project.ticket.global.auth.JwtProvider;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.transaction.annotation.Transactional;

@ActiveProfiles("test")
@AutoConfigureMockMvc
@SpringBootTest
@Transactional
class TicketMvpApiSmokeTest {
    @Autowired Clock clock;
    @Autowired SeatRepository seats;
    @Autowired UserRepository users;
    @Autowired AuthSessionRepository sessions;
    @Autowired JwtProvider jwt;
    @Autowired
    MockMvc mockMvc;

    ObjectMapper objectMapper = new ObjectMapper();

    @Test
    void mvpApiFlowCreatesConcertAndPurchasesTicket() throws Exception {
        String artistToken = token(TestUsers.artist("Artist"));
        String fanToken = token(TestUsers.fan("Fan"));

        Long concertId = createConcert(artistToken);
        Long seatId = firstSeatId(concertId);

        JsonNode ticket = purchaseTicket(fanToken, seatId).get(0);

        assertThat(ticket.path("ticketId").asLong()).isPositive();
        assertThat(ticket.path("seatId").asLong()).isEqualTo(seatId);
        assertThat(ticket.path("purchasePrice").asInt()).isEqualTo(10_000);
        assertThat(ticket.has("paymentId")).isFalse();
        assertThat(ticket.has("status")).isFalse();
        JsonNode myTickets = myTickets(fanToken);
        assertThat(myTickets).hasSize(1);
        assertThat(myTickets.get(0).path("concertTitle").asText()).isEqualTo("Spring Concert");
        assertThat(myTickets.get(0).path("section").asText()).isEqualTo("A");
        assertThat(myTickets.get(0).path("row").asInt()).isEqualTo(1);
        assertThat(myTickets.get(0).path("col").asInt()).isEqualTo(1);
        assertThat(myTickets.get(0).has("userId")).isFalse();
        assertThat(myTickets.get(0).has("paymentId")).isFalse();
        assertThat(myTickets.get(0).has("status")).isFalse();
        assertThat(myTickets.get(0).path("purchasePrice").asInt()).isEqualTo(10_000);
    }

    @Test
    void concertCreationRequiresAuthentication() throws Exception {
        mockMvc.perform(post("/api/concerts")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(concertCreateJson()))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void seatAvailabilitySummaryReturnsSectionCountsWithoutFullSeatList() throws Exception {
        String artistToken = token(TestUsers.artist("Artist"));
        Long concertId = createConcert(artistToken);

        String response = mockMvc.perform(get("/api/concerts/{concertId}/seats/availability", concertId))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.totalAvailable").value(15_000))
                .andExpect(jsonPath("$.sections.length()").value(10))
                .andExpect(jsonPath("$.sections[0].section").value("A"))
                .andExpect(jsonPath("$.sections[0].availableCount").value(1_500))
                .andReturn()
                .getResponse()
                .getContentAsString();

        assertThat(response).doesNotContain("seatId");
    }

    @Test
    void seatAvailabilitySectionReturnsOnlySelectedSectionSeats() throws Exception {
        String artistToken = token(TestUsers.artist("Artist"));
        Long concertId = createConcert(artistToken);

        String response = mockMvc.perform(get(
                        "/api/concerts/{concertId}/seats/availability/sections/{section}",
                        concertId,
                        "A"
                ))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.section").value("A"))
                .andExpect(jsonPath("$.availableCount").value(1_500))
                .andExpect(jsonPath("$.seats.length()").value(1_500))
                .andExpect(jsonPath("$.seats[0].seatId").isNumber())
                .andExpect(jsonPath("$.seats[0].row").value(1))
                .andExpect(jsonPath("$.seats[0].col").value(1))
                .andExpect(jsonPath("$.seats[0].price").value(10_000))
                .andExpect(jsonPath("$.seats[0].status").value("AVAILABLE"))
                .andReturn()
                .getResponse()
                .getContentAsString();

        assertThat(response).doesNotContain("concertId");
    }

    private String token(User user) {
        users.save(user);
        var session = sessions.save(AuthSession.create(user, clock.instant()));
        return jwt.createAccessToken(user.getId(), user.getRole(), session.getId());
    }

    private Long createConcert(String artistToken) throws Exception {
        String response = mockMvc.perform(post("/api/concerts")
                        .header("Authorization", bearer(artistToken))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(concertCreateJson()))
                .andExpect(status().isCreated())
                .andReturn()
                .getResponse()
                .getContentAsString();
        return objectMapper.readTree(response).path("concertId").asLong();
    }

    private Long firstSeatId(Long concertId) throws Exception {
        String response = mockMvc.perform(get("/api/concerts/{concertId}/seats", concertId))
                .andExpect(status().isOk())
                .andReturn()
                .getResponse()
                .getContentAsString();
        JsonNode seats = objectMapper.readTree(response);
        assertThat(seats).hasSize(15_000);
        return seats.get(0).path("seatId").asLong();
    }

    private JsonNode purchaseTicket(String fanToken, Long seatId) throws Exception {
        String response = mockMvc.perform(post("/api/tickets")
                        .header("Authorization", bearer(fanToken))
                        .header("X-Entry-Token", TestEntryTokens.forSeat(seats, seatId, jwt.parse(fanToken).userId(), clock))
                        .header("Idempotency-Key", "smoke-ticket-1")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json(Map.of("seatIds", java.util.List.of(seatId)))))
                .andExpect(status().isCreated())
                .andReturn()
                .getResponse()
                .getContentAsString();
        return objectMapper.readTree(response);
    }

    private JsonNode myTickets(String fanToken) throws Exception {
        String response = mockMvc.perform(get("/api/tickets/me")
                        .header("Authorization", bearer(fanToken)))
                .andExpect(status().isOk())
                .andReturn()
                .getResponse()
                .getContentAsString();
        return objectMapper.readTree(response);
    }

    private String json(Object value) throws Exception {
        return objectMapper.writeValueAsString(value);
    }

    private String concertCreateJson() throws Exception {
        return json(Map.of(
                "title", "Spring Concert",
                "venue", "Olympic Park",
                "concertDate", clock.instant().plus(30, ChronoUnit.DAYS).toString(),
                "ticketOpenDate", clock.instant().minus(1, ChronoUnit.DAYS).toString(),
                "ticketCloseDate", clock.instant().plus(20, ChronoUnit.DAYS).toString(),
                "price", 10000
        ));
    }

    private String bearer(String token) {
        return "Bearer " + token;
    }
}
