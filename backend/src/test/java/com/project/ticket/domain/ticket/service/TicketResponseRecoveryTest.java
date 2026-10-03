package com.project.ticket.domain.ticket.service;

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
import java.time.LocalDateTime;
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
        fan = users.save(User.createFan("Recovery Fan"));
        User artist = users.save(User.createArtist("Recovery Artist"));
        LocalDateTime now = LocalDateTime.now(clock);
        Concert concert = concerts.save(Concert.create(artist, "Recovery", "Venue",
                now.plusDays(30), now.minusDays(1), now.plusDays(20)));
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
        var first = service.purchase(fan.getId(), new TicketPurchaseRequest(seatId), key);
        doReturn(clock.instant().plusSeconds(86_401)).when(clock).instant();
        var recovered = service.purchase(fan.getId(), new TicketPurchaseRequest(seatId), key);
        assertThat(recovered).isEqualTo(first);
        assertThat(service.purchase(fan.getId(), new TicketPurchaseRequest(seatId), key)).isEqualTo(first);
        assertCommittedPurchase();
    }

    @Test
    void keyMissForAnotherUsersTicketReturnsSeatNotAvailableAndReleasesMarker() throws Exception {
        purchase(fan).andExpect(status().isCreated());
        User other = users.save(User.createFan("Other Fan"));
        purchase(other).andExpect(status().isConflict()).andExpect(jsonPath("$.code").value("SEAT_NOT_AVAILABLE"));
        purchase(other).andExpect(status().isConflict()).andExpect(jsonPath("$.code").value("SEAT_NOT_AVAILABLE"));
        assertCommittedPurchase();
    }

    @Test
    void releaseFailurePreservesOriginalPurchaseErrorCode() throws Exception {
        purchase(fan).andExpect(status().isCreated());
        doThrow(new DataAccessResourceFailureException("release failed"))
                .when(store).releaseIfOwner(anyString(), anyString());
        User other = users.save(User.createFan("Other Fan"));
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

    private ResultActions purchase(User user) throws Exception {
        return mvc.perform(post("/api/tickets")
                .header("Authorization", "Bearer " + jwt.createAccessToken(user.getId(), user.getRole()))
                .header("Idempotency-Key", key)
                .contentType(MediaType.APPLICATION_JSON).content("{\"seatId\":" + seatId + "}"));
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
