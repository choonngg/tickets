package com.project.ticket.api;

import com.project.ticket.support.TestUsers;
import com.project.ticket.support.TestEntryTokens;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.project.ticket.domain.auth.service.AuthService;
import com.project.ticket.domain.concert.entity.Concert;
import com.project.ticket.domain.concert.repository.ConcertRepository;
import com.project.ticket.domain.seat.entity.Seat;
import com.project.ticket.domain.seat.entity.SeatStatus;
import com.project.ticket.domain.seat.repository.SeatRepository;
import com.project.ticket.domain.ticket.repository.TicketRepository;
import com.project.ticket.domain.user.entity.User;
import com.project.ticket.domain.user.entity.UserRole;
import com.project.ticket.domain.user.repository.UserRepository;
import com.project.ticket.global.auth.JwtProvider;
import java.time.Clock;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.UUID;
import jakarta.servlet.http.Cookie;
import com.project.ticket.infra.google.GoogleOAuthClient;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import static org.mockito.Mockito.when;
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
class KnownDefectReproductionTest {
    @Autowired Clock clock;
    @Autowired MockMvc mockMvc;
    @MockitoBean GoogleOAuthClient google;
    @Autowired jakarta.persistence.EntityManager entityManager;
    @Autowired JwtProvider jwtProvider;
    @Autowired org.springframework.jdbc.core.JdbcTemplate jdbc;
    @Autowired UserRepository users;
    @Autowired ConcertRepository concerts;
    @Autowired SeatRepository seats;
    @Autowired TicketRepository tickets;

    @Test
    void h1RefreshWithoutAccessTokenSucceeds() throws Exception {
        Cookie login = login();

        mockMvc.perform(post("/api/auth/refresh").cookie(login))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.accessToken").isNotEmpty());
    }

    @Test
    void h2LogoutWithoutAccessTokenRevokesRefreshToken() throws Exception {
        Cookie login = login();
        Long sessionId = jdbc.queryForObject("SELECT session_id FROM refresh_token WHERE token_hash = ?", Long.class, AuthService.hash(login.getValue()));
        assertThat(jdbc.queryForObject("SELECT revoked_at FROM auth_session WHERE id = ?", Instant.class, sessionId)).isNull();

        mockMvc.perform(post("/api/auth/logout").cookie(login))
                .andExpect(status().isNoContent());

        entityManager.flush();
        assertThat(jdbc.queryForObject("SELECT revoked_at FROM auth_session WHERE id = ?", Instant.class, sessionId)).isNotNull();
        mockMvc.perform(post("/api/auth/refresh").cookie(login))
                .andExpect(status().isUnauthorized()).andExpect(jsonPath("$.code").value("REAUTH_REQUIRED"));
    }

    @Test
    void h3PurchaseBeforeOpeningIsRejected() throws Exception {
        User fan = users.save(TestUsers.fan("Fan"));
        Seat seat = createSeat(createConcert(true), 1);

        purchase(fan, seat, UUID.randomUUID().toString())
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("CONCERT_NOT_ON_SALE"));

        assertThat(seats.findById(seat.getId()).orElseThrow().getStatus()).isEqualTo(SeatStatus.AVAILABLE);
        assertThat(tickets.countBySeatId(seat.getId())).isZero();
    }

    @Test
    void h6SameKeyWithDifferentSeatReturns422() throws Exception {
        User fan = users.save(TestUsers.fan("Fan"));
        Concert concert = createConcert(false);
        Seat first = createSeat(concert, 1);
        Seat second = createSeat(concert, 2);
        String key = UUID.randomUUID().toString();
        purchase(fan, first, key).andExpect(status().isCreated())
                .andExpect(jsonPath("$[0].seatId").value(first.getId()));

        purchase(fan, second, key).andExpect(status().is(422))
                .andExpect(jsonPath("$.code").value("IDEMPOTENCY_CONFLICT"));

        assertThat(seats.findById(second.getId()).orElseThrow().getStatus()).isEqualTo(SeatStatus.AVAILABLE);
        assertThat(tickets.countBySeatId(second.getId())).isZero();
    }

    @Test
    void m1RefreshTokenAsBearerReturns401() throws Exception {
        Cookie login = login();

        mockMvc.perform(get("/api/tickets/me")
                        .header("Authorization", "Bearer " + login.getValue()))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value("UNAUTHORIZED"));
    }

    private Cookie login() throws Exception {
        String sub = UUID.randomUUID().toString();
        when(google.exchange("stub-code")).thenReturn(new GoogleOAuthClient.UserInfo(
                sub, sub + "@example.com", true, "Fan"));
        return mockMvc.perform(get("/api/auth/google/callback").param("code", "stub-code").param("state", "state")
                        .cookie(new Cookie("oauth_state", "state")))
                .andExpect(status().isFound()).andReturn().getResponse().getCookie("refresh_token");
    }

    private Concert createConcert(boolean beforeOpening) {
        User artist = users.save(TestUsers.artist("Artist"));
        Instant now = clock.instant();
        return concerts.save(Concert.create(artist, "Defect reproduction", "Venue",
                now.plus(30, ChronoUnit.DAYS), beforeOpening ? now.plus(1, ChronoUnit.DAYS) : now.minus(1, ChronoUnit.DAYS), now.plus(20, ChronoUnit.DAYS)));
    }

    private Seat createSeat(Concert concert, int col) {
        return seats.save(Seat.create(concert, "A", 1, col, 10_000));
    }

    private org.springframework.test.web.servlet.ResultActions purchase(User fan, Seat seat, String key)
            throws Exception {
        return mockMvc.perform(post("/api/tickets")
                .header("Authorization", "Bearer " + jwtProvider.createAccessToken(fan.getId(), fan.getRole(), 1L))
                .header("X-Entry-Token", TestEntryTokens.issue(seat.getConcert().getId(), fan.getId(), clock))
                .header("Idempotency-Key", key)
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"seatIds\":[" + seat.getId() + "]}"));
    }
}
