package com.project.ticket;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doThrow;

import com.project.ticket.domain.concert.entity.Concert;
import com.project.ticket.domain.concert.repository.ConcertRepository;
import com.project.ticket.domain.seat.entity.Seat;
import com.project.ticket.domain.seat.entity.SeatStatus;
import com.project.ticket.domain.seat.repository.SeatRepository;
import com.project.ticket.domain.ticket.controller.TicketController;
import com.project.ticket.domain.ticket.dto.TicketPurchaseRequest;
import com.project.ticket.domain.ticket.dto.TicketPurchaseResponse;
import com.project.ticket.domain.ticket.repository.TicketRepository;
import com.project.ticket.domain.ticket.service.TicketService;
import com.project.ticket.domain.user.entity.User;
import com.project.ticket.domain.user.entity.UserRole;
import com.project.ticket.domain.user.repository.UserRepository;
import com.project.ticket.global.auth.AuthenticatedUser;
import com.project.ticket.global.exception.BusinessException;
import com.project.ticket.global.exception.ErrorCode;
import com.project.ticket.infra.idempotency.RedisIdempotencyStore;
import com.project.ticket.support.IntegrationTestSupport;
import java.time.Clock;
import java.time.Duration;
import java.time.LocalDateTime;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.dao.DataAccessResourceFailureException;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;

// No test transaction: the purchase must really commit before Redis response storage fails.
class TicketResponseStorageDefectIntegrationTest extends IntegrationTestSupport {
    @Autowired TicketService ticketService;
    @Autowired TicketController controller;
    @Autowired Clock clock;
    @Autowired UserRepository users;
    @Autowired ConcertRepository concerts;
    @Autowired SeatRepository seats;
    @Autowired TicketRepository tickets;
    @Autowired StringRedisTemplate redis;
    @MockitoSpyBean RedisIdempotencyStore store;

    Long fanId;
    Long seatId;
    String key;
    String redisKey;

    @BeforeEach
    void injectResponseWriteFailure() {
        fanId = users.save(User.createFan("H7 Fan")).getId();
        User artist = users.save(User.createArtist("H7 Artist"));
        LocalDateTime now = LocalDateTime.now(clock);
        Concert concert = concerts.save(Concert.create(artist, "H7 Concert", "Venue",
                now.plusDays(30), now.minusDays(1), now.plusDays(20)));
        seatId = seats.save(Seat.create(concert, "A", 1, 1, 10_000)).getId();
        key = UUID.randomUUID().toString();
        redisKey = "idempotency:tickets:" + fanId + ":" + key;

        // Acquisition runs real Lua; only COMPLETE fails after the DB transaction commits.
        doThrow(new DataAccessResourceFailureException("H7: injected Redis COMPLETE failure"))
                .when(store).complete(anyString(), anyString(), anyString(), any());
        assertThat(redis.opsForValue().get(redisKey)).isNull();
    }

    @Test
    void h7ResponseWriteFailureDoesNotFailCommittedPurchase() {
        try {
            TicketPurchaseResponse response = assertDoesNotThrow(this::purchase);
            assertThat(response.seatId()).isEqualTo(seatId);
        } finally {
            assertCommittedPurchase();
        }
    }

    @Test
    void h7RetryRecoversCommittedTicketWhenRedisResponseIsMissing() {
        TicketPurchaseResponse first = assertDoesNotThrow(this::purchase);
        assertCommittedPurchase();
        assertThat(redis.opsForValue().get(redisKey)).contains("IN_PROGRESS");
        assertThat(redis.getExpire(redisKey)).isBetween(1L, 30L);
        assertThatThrownBy(this::purchase).isInstanceOfSatisfying(BusinessException.class,
                exception -> assertThat(exception.errorCode()).isEqualTo(ErrorCode.REQUEST_IN_PROGRESS));
        // Simulate the marker's TTL expiry without making the suite wait 30 seconds.
        assertThat(redis.expire(redisKey, Duration.ZERO)).isTrue();
        assertThat(redis.opsForValue().get(redisKey)).isNull();
        Long committedTicketId = tickets.findByUserIdOrderByCreatedAtDesc(fanId).getFirst().getId();

        TicketPurchaseResponse recovered = assertDoesNotThrow(this::purchase);

        assertThat(recovered).isEqualTo(first);
        assertThat(recovered.ticketId()).isEqualTo(committedTicketId);
        assertThat(recovered.seatId()).isEqualTo(seatId);
        assertCommittedPurchase();
    }

    @Test
    void keyMissForAnotherUsersTicketReturnsSeatNotAvailable() {
        purchase();
        Long otherId = users.save(User.createFan("Other H7 Fan")).getId();
        assertThatThrownBy(() -> ticketService.purchase(otherId, new TicketPurchaseRequest(seatId), key))
                .isInstanceOfSatisfying(BusinessException.class,
                        exception -> assertThat(exception.errorCode()).isEqualTo(ErrorCode.SEAT_NOT_AVAILABLE));
        assertCommittedPurchase();
    }

    @Test
    void releaseFailurePreservesOriginalPurchaseError() {
        purchase();
        doThrow(new DataAccessResourceFailureException("H7: injected Redis RELEASE failure"))
                .when(store).releaseIfOwner(anyString(), anyString());
        Long otherId = users.save(User.createFan("Other H7 Fan")).getId();
        assertThatThrownBy(() -> ticketService.purchase(otherId, new TicketPurchaseRequest(seatId), key))
                .isInstanceOfSatisfying(BusinessException.class,
                        exception -> assertThat(exception.errorCode()).isEqualTo(ErrorCode.SEAT_NOT_AVAILABLE));
        assertCommittedPurchase();
    }

    private TicketPurchaseResponse purchase() {
        var result = controller.purchase(new AuthenticatedUser(fanId, UserRole.FAN),
                new TicketPurchaseRequest(seatId), key);
        assertThat(result.getStatusCode().value()).isEqualTo(201);
        return result.getBody();
    }

    private void assertCommittedPurchase() {
        assertThat(tickets.countBySeatId(seatId)).isEqualTo(1);
        assertThat(seats.findById(seatId).orElseThrow().getStatus()).isEqualTo(SeatStatus.SOLD);
    }
}
