package com.project.ticket.api;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;
import com.project.ticket.domain.concert.entity.Concert;
import com.project.ticket.domain.concert.repository.ConcertRepository;
import com.project.ticket.domain.seat.entity.Seat;
import com.project.ticket.domain.seat.entity.SeatStatus;
import com.project.ticket.domain.seat.repository.SeatRepository;
import com.project.ticket.domain.ticket.repository.TicketRepository;
import com.project.ticket.domain.ticket.service.purchase.PurchaseStrategy;
import com.project.ticket.domain.ticket.service.purchase.TicketPurchaseProperties;
import com.project.ticket.domain.user.repository.UserRepository;
import com.project.ticket.global.auth.JwtProvider;
import com.project.ticket.infra.payment.PaymentClient;
import com.project.ticket.infra.payment.PaymentResult;
import com.project.ticket.support.TestEntryTokens;
import com.project.ticket.support.TestUsers;
import java.time.Clock;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.*;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.junit.jupiter.params.provider.CsvSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;

@SpringBootTest @AutoConfigureMockMvc @ActiveProfiles("test")
class MultiSeatPurchasePolicyTest {
    @Autowired MockMvc mvc;
    @Autowired UserRepository users;
    @Autowired ConcertRepository concerts;
    @Autowired SeatRepository seats;
    @Autowired TicketRepository tickets;
    @Autowired JwtProvider jwt;
    @Autowired Clock clock;
    @Autowired TicketPurchaseProperties properties;
    @MockitoBean PaymentClient payment;
    long userId, concertId;
    String auth;
    List<Long> ids;

    @BeforeEach void setup() {
        when(payment.pay(any(), any(), anyInt())).thenReturn(PaymentResult.success());
        var fan = users.save(TestUsers.fan("Policy"));
        userId = fan.getId();
        var artist = users.save(TestUsers.artist("Policy artist"));
        var now = clock.instant();
        var concert = concerts.save(Concert.create(artist, "Policy", "Venue", now.plusSeconds(7200),
                now.minusSeconds(60), now.plusSeconds(3600)));
        concertId = concert.getId();
        ids = java.util.stream.IntStream.range(0, 5)
                .mapToObj(i -> seats.save(Seat.create(concert, "A", 1, i + 1, (i + 1) * 10000)).getId()).toList();
        auth = "Bearer " + jwt.createAccessToken(fan.getId(), fan.getRole(), 1L);
    }
    @AfterEach void reset() { properties.setPurchaseStrategy(PurchaseStrategy.PESSIMISTIC); }
    String token() { return TestEntryTokens.issue(concertId, userId, clock); }
    ResultActions purchase(List<Long> ids, String token, String key) throws Exception {
        return mvc.perform(post("/api/tickets").header("Authorization", auth).header("X-Entry-Token", token)
                .header("Idempotency-Key", key).contentType(MediaType.APPLICATION_JSON)
                .content("{\"seatIds\":" + ids + "}"));
    }
    ResultActions purchase(List<Long> ids, String token) throws Exception {
        return purchase(ids, token, UUID.randomUUID().toString());
    }

    @ParameterizedTest @CsvSource({"OPTIMISTIC,1", "OPTIMISTIC,2", "OPTIMISTIC,3", "OPTIMISTIC,4",
            "PESSIMISTIC,1", "PESSIMISTIC,2", "PESSIMISTIC,3", "PESSIMISTIC,4",
            "DISTRIBUTED,1", "DISTRIBUTED,2", "DISTRIBUTED,3", "DISTRIBUTED,4"})
    void oneToFourSeatsUseOneSummedPayment(PurchaseStrategy strategy, int count) throws Exception {
        properties.setPurchaseStrategy(strategy);
        purchase(ids.subList(0, count).reversed(), token()).andExpect(status().isCreated())
                .andExpect(jsonPath("$.length()").value(count)).andExpect(jsonPath("$[0].seatId").value(ids.getFirst()));
        assertThat(tickets.findByUserIdOrderByCreatedAtDesc(userId)).hasSize(count);
        for (Long id : ids.subList(0, count)) assertThat(seats.findById(id).orElseThrow().getStatus()).isEqualTo(SeatStatus.SOLD);
        verify(payment, times(1)).pay(any(), any(), eq(count * (count + 1) / 2 * 10000));
    }

    @Test void multiSeatPurchaseIsAtomicAndLimitIsCumulative() throws Exception {
        String token = token();
        String key = UUID.randomUUID().toString();
        String first = purchase(ids.subList(0, 2), token, key).andExpect(status().isCreated())
                .andReturn().getResponse().getContentAsString();
        String replay = purchase(ids.subList(0, 2).reversed(), token, key).andExpect(status().isCreated())
                .andReturn().getResponse().getContentAsString();
        assertThat(replay).isEqualTo(first);
        purchase(ids.subList(2, 4), token).andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("ENTRY_TOKEN_INVALID"));
        String next = token();
        purchase(ids.subList(2, 5), next).andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("PURCHASE_LIMIT_EXCEEDED"));
        assertThat(tickets.findByUserIdOrderByCreatedAtDesc(userId)).hasSize(2);
        purchase(ids.subList(2, 4), next).andExpect(status().isCreated());
        assertThat(tickets.findByUserIdOrderByCreatedAtDesc(userId)).hasSize(4);
        mvc.perform(get("/api/tickets/me").header("Authorization", auth)).andExpect(status().isOk())
                .andExpect(jsonPath("$[0].concertId").value(concertId));
    }

    @Test void invalidCardinalityDuplicatesAndDifferentConcertAre400() throws Exception {
        String token = token();
        for (List<Long> selection : List.of(List.<Long>of(), ids, List.of(ids.getFirst(), ids.getFirst()))) {
            purchase(selection, token).andExpect(status().isBadRequest()).andExpect(jsonPath("$.code").value("INVALID_REQUEST"));
        }
        var artist = users.save(TestUsers.artist("Other artist"));
        var now = clock.instant();
        var other = concerts.save(Concert.create(artist, "Other", "Venue", now.plusSeconds(7200),
                now.minusSeconds(60), now.plusSeconds(3600)));
        var otherSeat = seats.save(Seat.create(other, "A", 1, 1, 10000));
        purchase(List.of(ids.getFirst(), otherSeat.getId()), token).andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("INVALID_REQUEST"));
        mvc.perform(post("/api/tickets").header("Authorization", auth).header("X-Entry-Token", token)
                .header("Idempotency-Key", UUID.randomUUID().toString()).contentType(MediaType.APPLICATION_JSON)
                .content("{\"seatId\":" + ids.getFirst() + "}"))
                .andExpect(status().isBadRequest()).andExpect(jsonPath("$.code").value("INVALID_REQUEST"));
        assertThat(tickets.findByUserIdOrderByCreatedAtDesc(userId)).isEmpty();
        verifyNoInteractions(payment);
    }

    @ParameterizedTest @EnumSource(PurchaseStrategy.class)
    void soldSeatRejectsWholeBatchAndSameTokenCanRetry(PurchaseStrategy strategy) throws Exception {
        properties.setPurchaseStrategy(strategy);
        var sold = seats.findById(ids.get(1)).orElseThrow();
        sold.completeSale();
        seats.saveAndFlush(sold);
        String token = token();
        purchase(ids.subList(0, 3), token).andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("SEAT_NOT_AVAILABLE"));
        assertThat(tickets.findByUserIdOrderByCreatedAtDesc(userId)).isEmpty();
        assertThat(seats.findById(ids.getFirst()).orElseThrow().getStatus()).isEqualTo(SeatStatus.AVAILABLE);
        verifyNoInteractions(payment);
        purchase(List.of(ids.getFirst(), ids.get(2)), token).andExpect(status().isCreated());
    }

    @ParameterizedTest @EnumSource(PurchaseStrategy.class)
    void paymentFailureRollsBackAllSeatsAndRetainsAdmission(PurchaseStrategy strategy) throws Exception {
        properties.setPurchaseStrategy(strategy);
        when(payment.pay(any(), any(), anyInt())).thenReturn(PaymentResult.failure(), PaymentResult.success());
        String token = token();
        String key = UUID.randomUUID().toString();
        purchase(ids.subList(0, 4), token, key).andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("PAYMENT_FAILED"));
        assertThat(tickets.findByUserIdOrderByCreatedAtDesc(userId)).isEmpty();
        for (Long id : ids) assertThat(seats.findById(id).orElseThrow().getStatus()).isEqualTo(SeatStatus.AVAILABLE);
        purchase(ids.subList(0, 4), token, key).andExpect(status().isCreated());
    }
}
