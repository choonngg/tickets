package com.project.ticket.api;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.project.ticket.domain.auth.dto.LoginRequest;
import com.project.ticket.domain.auth.dto.LoginResponse;
import com.project.ticket.domain.auth.dto.SignupRequest;
import com.project.ticket.domain.auth.service.AuthService;
import com.project.ticket.domain.auth.service.RefreshTokenStore;
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
import java.time.LocalDateTime;
import java.util.UUID;
import org.junit.jupiter.api.Disabled;
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
    @Autowired MockMvc mockMvc;
    @Autowired AuthService authService;
    @Autowired RefreshTokenStore refreshTokenStore;
    @Autowired JwtProvider jwtProvider;
    @Autowired UserRepository users;
    @Autowired ConcertRepository concerts;
    @Autowired SeatRepository seats;
    @Autowired TicketRepository tickets;

    @Test
    @Disabled("H1: AT 없이 refresh가 거부됨 — Phase 3에서 수정")
    void h1RefreshWithoutAccessTokenSucceeds() throws Exception {
        LoginResponse login = login();

        mockMvc.perform(post("/api/auth/token/refresh")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(refreshBody(login)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.accessToken").isNotEmpty());
    }

    @Test
    @Disabled("H2: AT 없이 logout이 거부되어 RT가 남음 — Phase 3에서 수정")
    void h2LogoutWithoutAccessTokenRevokesRefreshToken() throws Exception {
        LoginResponse login = login();
        Long userId = jwtProvider.parseUserId(login.refreshToken());
        assertThat(refreshTokenStore.find(userId)).contains(login.refreshToken());

        mockMvc.perform(post("/api/auth/logout")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(refreshBody(login)))
                .andExpect(status().isNoContent());

        assertThat(refreshTokenStore.find(userId)).isEmpty();
    }

    @Test
    @Disabled("H3: 오픈 전 구매가 허용됨 — Phase 1에서 수정")
    void h3PurchaseBeforeOpeningIsRejected() throws Exception {
        User fan = users.save(User.createFan("Fan"));
        Seat seat = createSeat(createConcert(true), 1);

        purchase(fan, seat, UUID.randomUUID().toString())
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("CONCERT_NOT_ON_SALE"));

        assertThat(seats.findById(seat.getId()).orElseThrow().getStatus()).isEqualTo(SeatStatus.AVAILABLE);
        assertThat(tickets.countBySeatId(seat.getId())).isZero();
    }

    @Test
    @Disabled("H6: 같은 키로 다른 좌석 요청 시 이전 응답이 반환됨 — Phase 1에서 수정")
    void h6SameKeyWithDifferentSeatReturns422() throws Exception {
        User fan = users.save(User.createFan("Fan"));
        Concert concert = createConcert(false);
        Seat first = createSeat(concert, 1);
        Seat second = createSeat(concert, 2);
        String key = UUID.randomUUID().toString();
        purchase(fan, first, key).andExpect(status().isCreated())
                .andExpect(jsonPath("$.seatId").value(first.getId()));

        purchase(fan, second, key).andExpect(status().is(422));

        assertThat(seats.findById(second.getId()).orElseThrow().getStatus()).isEqualTo(SeatStatus.AVAILABLE);
        assertThat(tickets.countBySeatId(second.getId())).isZero();
    }

    @Test
    @Disabled("M1: RT를 Bearer로 보내면 처리되지 않은 NPE 발생 — Phase 3에서 수정")
    void m1RefreshTokenAsBearerReturns401() throws Exception {
        LoginResponse login = login();

        mockMvc.perform(get("/api/tickets/me")
                        .header("Authorization", "Bearer " + login.refreshToken()))
                .andExpect(status().isUnauthorized());
    }

    private LoginResponse login() {
        String email = UUID.randomUUID() + "@example.com";
        authService.signup(new SignupRequest(email, "password123", "Fan", UserRole.FAN));
        return authService.login(new LoginRequest(email, "password123"));
    }

    private String refreshBody(LoginResponse login) {
        return "{\"refreshToken\":\"" + login.refreshToken() + "\"}";
    }

    private Concert createConcert(boolean beforeOpening) {
        User artist = users.save(User.createArtist("Artist"));
        LocalDateTime now = LocalDateTime.now();
        return concerts.save(Concert.create(artist, "Defect reproduction", "Venue",
                now.plusDays(30), beforeOpening ? now.plusDays(1) : now.minusDays(1), now.plusDays(20)));
    }

    private Seat createSeat(Concert concert, int col) {
        return seats.save(Seat.create(concert, "A", 1, col, 10_000));
    }

    private org.springframework.test.web.servlet.ResultActions purchase(User fan, Seat seat, String key)
            throws Exception {
        return mockMvc.perform(post("/api/tickets")
                .header("Authorization", "Bearer " + jwtProvider.createAccessToken(fan.getId(), fan.getRole()))
                .header("Idempotency-Key", key)
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"seatId\":" + seat.getId() + "}"));
    }
}
