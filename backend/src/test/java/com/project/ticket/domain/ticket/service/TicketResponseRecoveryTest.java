package com.project.ticket.domain.ticket.service;

import com.project.ticket.support.TestUsers;
import com.project.ticket.support.TestEntryTokens;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.doThrow;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.project.ticket.domain.concert.entity.Concert;
import com.project.ticket.domain.concert.repository.ConcertRepository;
import com.project.ticket.domain.seat.entity.Seat;
import com.project.ticket.domain.seat.entity.SeatStatus;
import com.project.ticket.domain.seat.repository.SeatRepository;
import com.project.ticket.domain.ticket.dto.TicketPurchaseRequest;
import com.project.ticket.domain.ticket.repository.TicketRepository;
import com.project.ticket.domain.user.entity.User;
import com.project.ticket.domain.user.repository.UserRepository;
import com.project.ticket.global.auth.JwtProvider;
import com.project.ticket.support.InMemoryIdempotencyStore;
import java.time.Clock;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.dao.DataAccessResourceFailureException;
import org.springframework.http.MediaType;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;

// No test transaction: verify the committed purchase with open-in-view disabled.
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
class TicketResponseRecoveryTest {
    @Autowired MockMvc mvc;
    @Autowired TicketService service;
    @Autowired UserRepository users;
    @Autowired ConcertRepository concerts;
    @Autowired SeatRepository seats;
    @Autowired TicketRepository tickets;
    @Autowired JwtProvider jwt;
    @MockitoSpyBean InMemoryIdempotencyStore store;
    @MockitoSpyBean Clock clock;

    User fan;
    Long seatId;
    String key;

    @BeforeEach
    void setUp() {
        fan = users.save(TestUsers.fan("Recovery Fan"));
        User artist = users.save(TestUsers.artist("Recovery Artist"));
        Instant now = clock.instant();
        Concert concert = concerts.save(Concert.create(artist, "Recovery", "Venue",
                now.plus(30, ChronoUnit.DAYS), now.minus(1, ChronoUnit.DAYS), now.plus(20, ChronoUnit.DAYS)));
        seatId = seats.save(Seat.create(concert, "A", 1, 1, 10_000)).getId();
        key = UUID.randomUUID().toString();
    }

    @Test
    void completeFailureKeeps201AndCommittedPurchaseThenRecoversAfterMarkerExpiry() throws Exception {
        doThrow(new DataAccessResourceFailureException("complete failed"))
                .when(store).complete(anyString(), anyString(), anyString(), any());
        String first = purchase(fan).andExpect(status().isCreated()).andReturn().getResponse().getContentAsString();
        assertCommittedPurchase();
        purchase(fan).andExpect(status().isConflict()).andExpect(jsonPath("$.code").value("REQUEST_IN_PROGRESS"));
        expireMarker();
        String recovered = purchase(fan).andExpect(status().isCreated()).andReturn().getResponse().getContentAsString();
        assertThat(recovered).isEqualTo(first);
        assertCommittedPurchase();
    }

    @Test
    void missingKeyRecoversOwnTicketAndCachesSameResponse() throws Exception {
        var first = service.purchase(fan.getId(), new TicketPurchaseRequest(java.util.List.of(seatId)), key, TestEntryTokens.forSeat(seats, seatId, fan.getId(), clock));
        doReturn(clock.instant().plusSeconds(86_401)).when(clock).instant();
        var recovered = service.purchase(fan.getId(), new TicketPurchaseRequest(java.util.List.of(seatId)), key, TestEntryTokens.forSeat(seats, seatId, fan.getId(), clock));
        assertThat(recovered).isEqualTo(first);
        assertThat(service.purchase(fan.getId(), new TicketPurchaseRequest(java.util.List.of(seatId)), key, TestEntryTokens.forSeat(seats, seatId, fan.getId(), clock))).isEqualTo(first);
        assertCommittedPurchase();
    }

    @Test
    void keyMissForAnotherUsersTicketReturnsSeatNotAvailableAndReleasesMarker() throws Exception {
        purchase(fan).andExpect(status().isCreated());
        User other = users.save(TestUsers.fan("Other Fan"));
        purchase(other).andExpect(status().isConflict()).andExpect(jsonPath("$.code").value("SEAT_NOT_AVAILABLE"));
        purchase(other).andExpect(status().isConflict()).andExpect(jsonPath("$.code").value("SEAT_NOT_AVAILABLE"));
        assertCommittedPurchase();
    }

    @Test
    void releaseFailurePreservesOriginalPurchaseErrorCode() throws Exception {
        purchase(fan).andExpect(status().isCreated());
        doThrow(new DataAccessResourceFailureException("release failed"))
                .when(store).releaseIfOwner(anyString(), anyString());
        User other = users.save(TestUsers.fan("Other Fan"));
        purchase(other).andExpect(status().isConflict()).andExpect(jsonPath("$.code").value("SEAT_NOT_AVAILABLE"));
    }

    @Test
    void ownerLossDuringCompleteKeeps201AndAllowsDbRecovery() throws Exception {
        doReturn(false).when(store).complete(anyString(), anyString(), anyString(), any());
        String first = purchase(fan).andExpect(status().isCreated()).andReturn().getResponse().getContentAsString();
        expireMarker();
        String recovered = purchase(fan).andExpect(status().isCreated()).andReturn().getResponse().getContentAsString();
        assertThat(recovered).isEqualTo(first);
        assertCommittedPurchase();
    }

    @Test
    void multiSeatResponseRecoversOnlyTheSamePurchaseKeyWithConsumedToken() {
        var concert = seats.findById(seatId).orElseThrow().getConcert();
        var second = seats.save(Seat.create(concert, "A", 1, 2, 20000));
        var request = new TicketPurchaseRequest(java.util.List.of(second.getId(), seatId));
        String token = TestEntryTokens.forSeat(seats, seatId, fan.getId(), clock);
        doThrow(new DataAccessResourceFailureException("complete failed"))
                .when(store).complete(anyString(), anyString(), anyString(), any());
        var first = service.purchase(fan.getId(), request, key, token);
        assertThat(first).hasSize(2);
        expireMarker();
        var recovered = service.purchase(fan.getId(), new TicketPurchaseRequest(java.util.List.of(seatId, second.getId())), key, token);
        assertThat(recovered).isEqualTo(first);
        assertThat(tickets.findByUserIdOrderByCreatedAtDesc(fan.getId())).hasSize(2);
        expireMarker();
        org.assertj.core.api.Assertions.assertThatThrownBy(() -> service.purchase(fan.getId(),
                new TicketPurchaseRequest(java.util.List.of(seatId)), key, token))
                .isInstanceOfSatisfying(com.project.ticket.global.exception.BusinessException.class,
                        e -> assertThat(e.errorCode()).isEqualTo(com.project.ticket.global.exception.ErrorCode.IDEMPOTENCY_CONFLICT));
    }

    @Test
    void newKeyDoesNotMergePreviouslyPurchasedSeatsIntoANewBatch() {
        var first = service.purchase(fan.getId(), new TicketPurchaseRequest(java.util.List.of(seatId)), key,
                TestEntryTokens.forSeat(seats, seatId, fan.getId(), clock));
        var concert = seats.findById(seatId).orElseThrow().getConcert();
        var additional = seats.save(Seat.create(concert, "A", 1, 2, 20000));
        String nextToken = TestEntryTokens.forSeat(seats, seatId, fan.getId(), clock);
        org.assertj.core.api.Assertions.assertThatThrownBy(() -> service.purchase(fan.getId(),
                new TicketPurchaseRequest(java.util.List.of(seatId, additional.getId())), "new-key", nextToken))
                .isInstanceOfSatisfying(com.project.ticket.global.exception.BusinessException.class,
                        e -> assertThat(e.errorCode()).isEqualTo(com.project.ticket.global.exception.ErrorCode.SEAT_NOT_AVAILABLE));
        assertThat(tickets.findByUserIdOrderByCreatedAtDesc(fan.getId())).hasSize(1);
        assertThat(seats.findById(additional.getId()).orElseThrow().getStatus()).isEqualTo(SeatStatus.AVAILABLE);
    }

    private ResultActions purchase(User user) throws Exception {
        return mvc.perform(post("/api/tickets")
                .header("Authorization", "Bearer " + jwt.createAccessToken(user.getId(), user.getRole(), 1L))
                .header("X-Entry-Token", TestEntryTokens.forSeat(seats, seatId, user.getId(), clock))
                .header("Idempotency-Key", key)
                .contentType(MediaType.APPLICATION_JSON).content("{\"seatIds\":[" + seatId + "]}"));
    }

    private void expireMarker() {
        Instant afterTtl = clock.instant().plusSeconds(31);
        doReturn(afterTtl).when(clock).instant();
    }

    private void assertCommittedPurchase() {
        assertThat(tickets.countBySeatId(seatId)).isEqualTo(1);
        assertThat(seats.findById(seatId).orElseThrow().getStatus()).isEqualTo(SeatStatus.SOLD);
    }
}
