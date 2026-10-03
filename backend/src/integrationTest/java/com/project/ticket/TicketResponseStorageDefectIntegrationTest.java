package com.project.ticket;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.spy;

import com.project.ticket.domain.concert.entity.Concert;
import com.project.ticket.domain.concert.repository.ConcertRepository;
import com.project.ticket.domain.seat.entity.Seat;
import com.project.ticket.domain.seat.entity.SeatStatus;
import com.project.ticket.domain.seat.repository.SeatRepository;
import com.project.ticket.domain.ticket.dto.TicketPurchaseRequest;
import com.project.ticket.domain.ticket.dto.TicketPurchaseResponse;
import com.project.ticket.domain.ticket.repository.TicketRepository;
import com.project.ticket.domain.ticket.service.TicketService;
import com.project.ticket.domain.user.entity.User;
import com.project.ticket.domain.user.repository.UserRepository;
import com.project.ticket.support.IntegrationTestSupport;
import java.time.Duration;
import java.time.LocalDateTime;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Disabled;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.dao.DataAccessResourceFailureException;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ValueOperations;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;

// No test transaction: the purchase must really commit before Redis response storage fails.
@Disabled("H7: Redis 응답 저장 실패·재시도 복구 결함 — Phase 1에서 수정")
class TicketResponseStorageDefectIntegrationTest extends IntegrationTestSupport {
    @Autowired TicketService ticketService;
    @Autowired UserRepository users;
    @Autowired ConcertRepository concerts;
    @Autowired SeatRepository seats;
    @Autowired TicketRepository tickets;
    @MockitoSpyBean StringRedisTemplate redis;

    Long fanId;
    Long seatId;
    String key;
    String redisKey;

    @BeforeEach
    void injectResponseWriteFailure() {
        fanId = users.save(User.createFan("H7 Fan")).getId();
        User artist = users.save(User.createArtist("H7 Artist"));
        LocalDateTime now = LocalDateTime.now();
        Concert concert = concerts.save(Concert.create(artist, "H7 Concert", "Venue",
                now.plusDays(30), now.minusDays(1), now.plusDays(20)));
        seatId = seats.save(Seat.create(concert, "A", 1, 1, 10_000)).getId();
        key = UUID.randomUUID().toString();
        redisKey = "idempotency:tickets:" + fanId + ":" + key;

        // GET remains a real Redis call; only the response SET fails after DB commit.
        ValueOperations<String, String> values = spy(redis.opsForValue());
        doReturn(values).when(redis).opsForValue();
        doThrow(new DataAccessResourceFailureException("H7: injected Redis response SET failure"))
                .when(values).set(eq(redisKey), any(String.class), any(Duration.class));
        assertThat(values.get(redisKey)).isNull();
    }

    @Test
    @Disabled("H7: 커밋 후 Redis 저장 실패가 구매 실패로 전파됨 — Phase 1에서 수정")
    void h7ResponseWriteFailureDoesNotFailCommittedPurchase() {
        try {
            TicketPurchaseResponse response = assertDoesNotThrow(this::purchase);
            assertThat(response.seatId()).isEqualTo(seatId);
        } finally {
            assertCommittedPurchase();
        }
    }

    @Test
    @Disabled("H7: 응답 저장 실패 후 재시도에 DB 복구 경로가 없음 — Phase 1에서 수정")
    void h7RetryRecoversCommittedTicketWhenRedisResponseIsMissing() {
        try {
            purchase();
        } catch (DataAccessResourceFailureException expected) {
            // The first response is lost in the defective implementation; retry the identical request.
        }
        assertCommittedPurchase();
        assertThat(redis.opsForValue().get(redisKey)).isNull();
        Long committedTicketId = tickets.findByUserIdOrderByCreatedAtDesc(fanId).getFirst().getId();

        TicketPurchaseResponse recovered = assertDoesNotThrow(this::purchase);

        assertThat(recovered.ticketId()).isEqualTo(committedTicketId);
        assertThat(recovered.seatId()).isEqualTo(seatId);
        assertCommittedPurchase();
    }

    private TicketPurchaseResponse purchase() {
        return ticketService.purchase(fanId, new TicketPurchaseRequest(seatId), key);
    }

    private void assertCommittedPurchase() {
        assertThat(tickets.countBySeatId(seatId)).isEqualTo(1);
        assertThat(seats.findById(seatId).orElseThrow().getStatus()).isEqualTo(SeatStatus.SOLD);
    }
}
