package com.project.ticket.domain.ticket.service.purchase;

import com.project.ticket.support.TestUsers;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.project.ticket.domain.concert.entity.Concert;
import com.project.ticket.domain.concert.repository.ConcertRepository;
import com.project.ticket.domain.seat.entity.Seat;
import com.project.ticket.domain.seat.entity.SeatStatus;
import com.project.ticket.domain.seat.repository.SeatRepository;
import com.project.ticket.domain.ticket.repository.TicketRepository;
import com.project.ticket.domain.user.entity.User;
import com.project.ticket.domain.user.repository.UserRepository;
import com.project.ticket.global.exception.BusinessException;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.time.temporal.ChronoUnit;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.context.annotation.Primary;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.transaction.annotation.Transactional;

@ActiveProfiles("test")
@SpringBootTest
@Transactional
@Import(TicketPurchaseSaleWindowTest.FixedClockConfiguration.class)
class TicketPurchaseSaleWindowTest {
    @Autowired Clock clock;
    @Autowired TicketPurchaseProperties properties;
    @Autowired TicketPurchaseStrategyRouter router;
    @Autowired UserRepository users;
    @Autowired ConcertRepository concerts;
    @Autowired SeatRepository seats;
    @Autowired TicketRepository tickets;

    @AfterEach
    void resetStrategy() {
        properties.setPurchaseStrategy(PurchaseStrategy.DISTRIBUTED);
    }

    @ParameterizedTest
    @EnumSource(PurchaseStrategy.class)
    void purchaseBeforeOpeningIsRejected(PurchaseStrategy strategy) {
        Instant now = clock.instant();
        reject(strategy, createSeat(now.plus(1, ChronoUnit.MICROS), now.plus(1, ChronoUnit.DAYS), false), false,
                "CONCERT_NOT_ON_SALE");
    }

    @ParameterizedTest
    @EnumSource(PurchaseStrategy.class)
    void purchaseAfterClosingIsRejected(PurchaseStrategy strategy) {
        Instant now = clock.instant();
        reject(strategy, createSeat(now.minus(1, ChronoUnit.DAYS), now.minusNanos(1), false), false,
                "CONCERT_NOT_ON_SALE");
    }

    @ParameterizedTest
    @EnumSource(PurchaseStrategy.class)
    void purchaseAtClosingIsRejected(PurchaseStrategy strategy) {
        Instant now = clock.instant();
        reject(strategy, createSeat(now.minus(1, ChronoUnit.DAYS), now, false), false,
                "CONCERT_NOT_ON_SALE");
    }

    @ParameterizedTest
    @EnumSource(PurchaseStrategy.class)
    void purchaseOfCancelledConcertIsRejected(PurchaseStrategy strategy) {
        Instant now = clock.instant();
        reject(strategy, createSeat(now.minus(1, ChronoUnit.DAYS), now.plus(1, ChronoUnit.DAYS), true), false,
                "CONCERT_NOT_ON_SALE");
    }

    @ParameterizedTest
    @EnumSource(PurchaseStrategy.class)
    void purchaseByDeletedUserIsRejected(PurchaseStrategy strategy) {
        Instant now = clock.instant();
        reject(strategy, createSeat(now.minus(1, ChronoUnit.DAYS), now.plus(1, ChronoUnit.DAYS), false), true,
                "USER_NOT_FOUND");
    }

    @ParameterizedTest
    @EnumSource(PurchaseStrategy.class)
    void purchaseAtOpeningIsAllowed(PurchaseStrategy strategy) {
        properties.setPurchaseStrategy(strategy);
        Instant now = clock.instant();
        Seat seat = createSeat(now, now.plus(1, ChronoUnit.DAYS), false);
        User fan = users.save(TestUsers.fan("Fan"));

        var response = router.current().purchase(fan.getId(), java.util.List.of(seat.getId()), "purchase", "admission");

        assertThat(response.getFirst().seatId()).isEqualTo(seat.getId());
        assertThat(tickets.findById(response.getFirst().ticketId()).orElseThrow().getCreatedAt()).isEqualTo(now);
        assertThat(seat.getStatus()).isEqualTo(SeatStatus.SOLD);
        assertThat(tickets.countBySeatId(seat.getId())).isEqualTo(1);
    }

    private void reject(PurchaseStrategy strategy, Seat seat, boolean deleted, String code) {
        properties.setPurchaseStrategy(strategy);
        User fan = TestUsers.fan("Fan");
        ReflectionTestUtils.setField(fan, "deletedAt", deleted ? clock.instant() : null);
        users.save(fan);

        assertThatThrownBy(() -> router.current().purchase(fan.getId(), java.util.List.of(seat.getId()), "purchase", "admission"))
                .isInstanceOfSatisfying(BusinessException.class,
                        exception -> assertThat(exception.errorCode().name()).isEqualTo(code));

        assertThat(seat.getStatus()).isEqualTo(SeatStatus.AVAILABLE);
        assertThat(tickets.countBySeatId(seat.getId())).isZero();
    }

    private Seat createSeat(Instant open, Instant close, boolean cancelled) {
        User artist = users.save(TestUsers.artist("Artist"));
        Concert concert = Concert.create(artist, "Concert", "Venue", close.plus(1, ChronoUnit.DAYS), open, close);
        if (cancelled) {
            ReflectionTestUtils.setField(concert, "cancelledAt", clock.instant());
        }
        concerts.save(concert);
        return seats.save(Seat.create(concert, "A", 1, 1, 10_000));
    }

    @TestConfiguration
    static class FixedClockConfiguration {
        @Bean
        @Primary
        Clock fixedClock() {
            return Clock.fixed(Instant.parse("2026-10-03T03:00:00Z"), ZoneOffset.UTC);
        }
    }
}
