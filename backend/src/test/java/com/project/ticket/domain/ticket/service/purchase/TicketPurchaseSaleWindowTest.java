package com.project.ticket.domain.ticket.service.purchase;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.project.ticket.domain.concert.entity.Concert;
import com.project.ticket.domain.concert.entity.ConcertStatus;
import com.project.ticket.domain.concert.repository.ConcertRepository;
import com.project.ticket.domain.payment.repository.PaymentRepository;
import com.project.ticket.domain.seat.entity.Seat;
import com.project.ticket.domain.seat.entity.SeatStatus;
import com.project.ticket.domain.seat.repository.SeatRepository;
import com.project.ticket.domain.ticket.repository.TicketRepository;
import com.project.ticket.domain.user.entity.User;
import com.project.ticket.domain.user.repository.UserRepository;
import com.project.ticket.global.exception.BusinessException;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneId;
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
    @Autowired PaymentRepository payments;

    @AfterEach
    void resetStrategy() {
        properties.setPurchaseStrategy(PurchaseStrategy.DISTRIBUTED);
    }

    @ParameterizedTest
    @EnumSource(PurchaseStrategy.class)
    void purchaseBeforeOpeningIsRejected(PurchaseStrategy strategy) {
        LocalDateTime now = LocalDateTime.now(clock);
        reject(strategy, createSeat(now.plusNanos(1), now.plusDays(1), false), false,
                "CONCERT_NOT_ON_SALE");
    }

    @ParameterizedTest
    @EnumSource(PurchaseStrategy.class)
    void purchaseAfterClosingIsRejected(PurchaseStrategy strategy) {
        LocalDateTime now = LocalDateTime.now(clock);
        reject(strategy, createSeat(now.minusDays(1), now.minusNanos(1), false), false,
                "CONCERT_NOT_ON_SALE");
    }

    @ParameterizedTest
    @EnumSource(PurchaseStrategy.class)
    void purchaseAtClosingIsRejected(PurchaseStrategy strategy) {
        LocalDateTime now = LocalDateTime.now(clock);
        reject(strategy, createSeat(now.minusDays(1), now, false), false,
                "CONCERT_NOT_ON_SALE");
    }

    @ParameterizedTest
    @EnumSource(PurchaseStrategy.class)
    void purchaseOfCancelledConcertIsRejected(PurchaseStrategy strategy) {
        LocalDateTime now = LocalDateTime.now(clock);
        reject(strategy, createSeat(now.minusDays(1), now.plusDays(1), true), false,
                "CONCERT_NOT_ON_SALE");
    }

    @ParameterizedTest
    @EnumSource(PurchaseStrategy.class)
    void purchaseByDeletedUserIsRejected(PurchaseStrategy strategy) {
        LocalDateTime now = LocalDateTime.now(clock);
        reject(strategy, createSeat(now.minusDays(1), now.plusDays(1), false), true,
                "USER_NOT_FOUND");
    }

    @ParameterizedTest
    @EnumSource(PurchaseStrategy.class)
    void purchaseAtOpeningIsAllowed(PurchaseStrategy strategy) {
        properties.setPurchaseStrategy(strategy);
        LocalDateTime now = LocalDateTime.now(clock);
        Seat seat = createSeat(now, now.plusDays(1), false);
        User fan = users.save(User.createFan("Fan"));

        var response = router.current().purchase(fan.getId(), seat.getId());

        assertThat(response.seatId()).isEqualTo(seat.getId());
        assertThat(seat.getStatus()).isEqualTo(SeatStatus.SOLD);
        assertThat(tickets.countBySeatId(seat.getId())).isEqualTo(1);
    }

    private void reject(PurchaseStrategy strategy, Seat seat, boolean deleted, String code) {
        properties.setPurchaseStrategy(strategy);
        User fan = User.createFan("Fan");
        ReflectionTestUtils.setField(fan, "deleted", deleted);
        users.save(fan);
        long paymentCount = payments.count();

        assertThatThrownBy(() -> router.current().purchase(fan.getId(), seat.getId()))
                .isInstanceOfSatisfying(BusinessException.class,
                        exception -> assertThat(exception.errorCode().name()).isEqualTo(code));

        assertThat(seat.getStatus()).isEqualTo(SeatStatus.AVAILABLE);
        assertThat(tickets.countBySeatId(seat.getId())).isZero();
        assertThat(payments.count()).isEqualTo(paymentCount);
    }

    private Seat createSeat(LocalDateTime open, LocalDateTime close, boolean cancelled) {
        User artist = users.save(User.createArtist("Artist"));
        Concert concert = Concert.create(artist, "Concert", "Venue", close.plusDays(1), open, close);
        if (cancelled) {
            ReflectionTestUtils.setField(concert, "status", ConcertStatus.CANCELLED);
        }
        concerts.save(concert);
        return seats.save(Seat.create(concert, "A", 1, 1, 10_000));
    }

    @TestConfiguration
    static class FixedClockConfiguration {
        @Bean
        @Primary
        Clock fixedClock() {
            return Clock.fixed(Instant.parse("2026-10-03T03:00:00Z"), ZoneId.of("Asia/Seoul"));
        }
    }
}
