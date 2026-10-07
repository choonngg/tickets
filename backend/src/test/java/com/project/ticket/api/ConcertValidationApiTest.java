package com.project.ticket.api;

import com.project.ticket.support.TestUsers;
import com.project.ticket.support.TestEntryTokens;
import com.project.ticket.domain.concert.entity.Concert;
import com.project.ticket.domain.seat.entity.Seat;
import com.project.ticket.domain.seat.repository.SeatRepository;
import java.time.Clock;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

import com.project.ticket.domain.concert.repository.ConcertRepository;
import com.project.ticket.domain.user.entity.User;
import com.project.ticket.domain.user.repository.UserRepository;
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
class ConcertValidationApiTest {
    @Autowired MockMvc mvc;
    @Autowired SeatRepository seats;
    @Autowired Clock clock;
    @Autowired UserRepository users;
    @Autowired ConcertRepository concerts;
    @Autowired JwtProvider jwtProvider;

    @Test
    void invalidDateOrderReturns400WithoutSaving() throws Exception {
        String token = artistToken();
        long before = concerts.count();
        for (String[] dates : new String[][] {
                {"2030-01-01T10:00:00Z", "2030-01-01T10:00:00Z"},
                {"2030-01-02T10:00:00Z", "2030-01-01T10:00:00Z"},
                {"2030-01-01T10:00:00Z", "2030-01-04T10:00:00Z"}}) {
            mvc.perform(post("/api/concerts").header("Authorization", token)
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(body("Title", "Venue", dates[0], dates[1])))
                    .andExpect(status().isBadRequest())
                    .andExpect(content().json("{\"code\":\"INVALID_REQUEST\",\"message\":\"Request body is invalid.\"}"));
        }
        assertThat(concerts.count()).isEqualTo(before);
    }

    @Test
    void oversizedStringsReturn400WithoutSaving() throws Exception {
        String token = artistToken();
        long before = concerts.count();
        for (String[] strings : new String[][] {{"T".repeat(101), "Venue"}, {"Title", "V".repeat(256)}}) {
            mvc.perform(post("/api/concerts").header("Authorization", token)
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(body(strings[0], strings[1], "2030-01-01T10:00:00Z", "2030-01-02T10:00:00Z")))
                    .andExpect(status().isBadRequest())
                    .andExpect(jsonPath("$.code").value("INVALID_REQUEST"));
        }
        assertThat(concerts.count()).isEqualTo(before);
    }

    @Test
    void maximumLengthsAndClosingAtConcertTimeAreAccepted() throws Exception {
        mvc.perform(post("/api/concerts").header("Authorization", artistToken())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body("T".repeat(100), "V".repeat(255), "2030-01-01T10:00:00Z", "2030-01-03T10:00:00Z")))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.venue").value("V".repeat(255)))
                .andExpect(jsonPath("$.concertDate").value("2030-01-03T10:00:00Z"))
                .andExpect(jsonPath("$.ticketOpenDate").value("2030-01-01T10:00:00Z"))
                .andExpect(jsonPath("$.ticketCloseDate").value("2030-01-03T10:00:00Z"));
    }

    @Test
    void offsetDatesAreNormalizedToUtcInCreateAndReadResponses() throws Exception {
        String response = mvc.perform(post("/api/concerts").header("Authorization", artistToken())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body("Offset Concert", "Venue", "2030-01-01T19:00:00+09:00",
                                "2030-01-03T19:00:00+09:00")
                                .replace("2030-01-03T10:00:00Z", "2030-01-03T19:00:00+09:00")))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.concertDate").value("2030-01-03T10:00:00Z"))
                .andExpect(jsonPath("$.ticketOpenDate").value("2030-01-01T10:00:00Z"))
                .andExpect(jsonPath("$.ticketCloseDate").value("2030-01-03T10:00:00Z"))
                .andReturn().getResponse().getContentAsString();
        long id = concerts.findAll().stream().filter(c -> c.getTitle().equals("Offset Concert"))
                .findFirst().orElseThrow().getId();
        mvc.perform(get("/api/concerts/{id}", id))
                .andExpect(status().isOk())
                .andExpect(content().json(response));
        mvc.perform(get("/api/concerts"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[?(@.concertId == " + id + ")].concertDate")
                        .value(org.hamcrest.Matchers.contains("2030-01-03T10:00:00Z")));
    }

    @Test
    void nanosecondDatesAreTruncatedToMicrosAndMatchBetweenCreateAndReadResponses() throws Exception {
        String response = mvc.perform(post("/api/concerts").header("Authorization", artistToken())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body("Nano Concert", "Venue", "2030-01-01T10:00:00.0000001Z",
                                "2030-01-02T10:00:00.123456789Z")))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.ticketOpenDate").value("2030-01-01T10:00:00Z"))
                .andExpect(jsonPath("$.ticketCloseDate").value("2030-01-02T10:00:00.123456Z"))
                .andReturn().getResponse().getContentAsString();
        long id = concerts.findAll().stream().filter(c -> c.getTitle().equals("Nano Concert"))
                .findFirst().orElseThrow().getId();
        mvc.perform(get("/api/concerts/{id}", id))
                .andExpect(status().isOk())
                .andExpect(content().json(response));
    }

    @Test
    void datesEqualAfterMicrosecondTruncationReturn400WithoutSaving() throws Exception {
        String token = artistToken();
        long before = concerts.count();
        mvc.perform(post("/api/concerts").header("Authorization", token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body("Title", "Venue", "2030-01-01T10:00:00.0000001Z",
                                "2030-01-01T10:00:00.0000002Z")))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("INVALID_REQUEST"));
        assertThat(concerts.count()).isEqualTo(before);
    }

    @Test
    void datesWithoutOffsetAreRejectedWithoutSaving() throws Exception {
        String token = artistToken();
        long before = concerts.count();
        mvc.perform(post("/api/concerts").header("Authorization", token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body("Title", "Venue", "2030-01-01T10:00:00Z", "2030-01-02T10:00:00Z")
                                .replace("Z", "")))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("INVALID_REQUEST"));
        assertThat(concerts.count()).isEqualTo(before);
    }

    @Test
    void purchaseWithoutIdempotencyKeyReturns400Json() throws Exception {
        User fan = users.save(TestUsers.fan("Fan"));
        var artist = users.save(TestUsers.artist("Artist"));
        var now = clock.instant();
        var concert = concerts.save(Concert.create(artist, "Missing key", "Venue",
                now.plusSeconds(7200), now.minusSeconds(1), now.plusSeconds(3600)));
        var seat = seats.save(Seat.create(concert, "A", 1, 1, 10000));
        mvc.perform(post("/api/tickets")
                        .header("Authorization", "Bearer " + jwtProvider.createAccessToken(fan.getId(), fan.getRole(), 1L))
                        .header("X-Entry-Token", TestEntryTokens.issue(concert.getId(), fan.getId(), clock))
                        .contentType(MediaType.APPLICATION_JSON).content("{\"seatIds\":[" + seat.getId() + "]}"))
                .andExpect(status().isBadRequest())
                .andExpect(content().json("{\"code\":\"IDEMPOTENCY_KEY_REQUIRED\",\"message\":\"Idempotency-Key header is required.\"}"))
                .andExpect(jsonPath("$.trace").doesNotExist());
    }

    private String artistToken() {
        User artist = users.save(TestUsers.artist("Artist"));
        return "Bearer " + jwtProvider.createAccessToken(artist.getId(), artist.getRole(), 1L);
    }

    private String body(String title, String venue, String open, String close) {
        return """
                {"title":"%s","venue":"%s","concertDate":"2030-01-03T10:00:00Z",
                 "ticketOpenDate":"%s","ticketCloseDate":"%s","price":10000}
                """.formatted(title, venue, open, close);
    }
}
