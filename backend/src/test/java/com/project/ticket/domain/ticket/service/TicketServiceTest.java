package com.project.ticket.domain.ticket.service;

import com.project.ticket.support.TestUsers;
import com.project.ticket.support.TestEntryTokens;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.project.ticket.domain.concert.dto.ConcertCreateRequest;
import com.project.ticket.domain.concert.service.ConcertService;
import com.project.ticket.domain.seat.entity.SeatStatus;
import com.project.ticket.domain.seat.repository.SeatRepository;
import com.project.ticket.domain.ticket.dto.TicketPurchaseRequest;
import com.project.ticket.domain.ticket.dto.TicketPurchaseResponse;
import com.project.ticket.domain.ticket.dto.TicketViewResponse;
import com.project.ticket.domain.ticket.repository.TicketRepository;
import com.project.ticket.domain.ticket.service.purchase.PurchaseStrategy;
import com.project.ticket.domain.ticket.service.purchase.TicketPurchaseProperties;
import com.project.ticket.domain.user.entity.User;
import com.project.ticket.domain.user.repository.UserRepository;
import com.project.ticket.global.exception.BusinessException;
import com.project.ticket.global.exception.ErrorCode;
import com.project.ticket.support.FailingPaymentClient;
import java.time.Clock;
import java.time.temporal.ChronoUnit;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.context.annotation.Primary;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.transaction.TestTransaction;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.transaction.annotation.Transactional;

@ActiveProfiles("test")
@SpringBootTest
@Transactional
@Import(TicketServiceTest.PaymentConfiguration.class)
class TicketServiceTest {
    @Autowired Clock clock;

    @Autowired
    TicketService ticketService;

    @Autowired
    ConcertService concertService;

    @Autowired
    UserRepository userRepository;

    @Autowired
    SeatRepository seatRepository;

    @Autowired
    FailingPaymentClient mockPaymentClient;

    @Autowired
    TicketRepository ticketRepository;

    @Autowired
    TicketPurchaseProperties properties;

    @AfterEach
    void resetStrategy() {
        properties.setPurchaseStrategy(PurchaseStrategy.DISTRIBUTED);
    }

    @Test
    void purchaseCreatesTicketAndSellsSeat() {
        Long fanId = createFan();
        Long seatId = createConcertSeat();

        var response = ticketService.purchase(fanId, new TicketPurchaseRequest(java.util.List.of(seatId)), "key-1", TestEntryTokens.forSeat(seatRepository, seatId, fanId, clock));

        assertThat(response.getFirst().seatId()).isEqualTo(seatId);
        assertThat(response.getFirst().purchasePrice()).isEqualTo(100_000);
        var ticket = ticketRepository.findById(response.getFirst().ticketId()).orElseThrow();
        assertThat(ticket.getPurchasePrice()).isEqualTo(100_000);
        ReflectionTestUtils.setField(ticket.getSeat(), "price", 200_000);
        assertThat(TicketPurchaseResponse.from(ticket).purchasePrice())
                .isEqualTo(100_000);
        assertThat(TicketViewResponse.from(ticket).purchasePrice())
                .isEqualTo(100_000);
        assertThat(seatRepository.findById(seatId).orElseThrow().getStatus()).isEqualTo(SeatStatus.SOLD);
    }

    @ParameterizedTest
    @EnumSource(PurchaseStrategy.class)
    void paymentFailureRollsBackWithoutTicket(PurchaseStrategy strategy) {
        properties.setPurchaseStrategy(strategy);
        Long fanId = createFan();
        Long seatId = createConcertSeat();
        TestTransaction.flagForCommit();
        TestTransaction.end();
        mockPaymentClient.failNextPayment();

        try {
            assertThatThrownBy(() -> ticketService.purchase(fanId, new TicketPurchaseRequest(java.util.List.of(seatId)), "key-2", TestEntryTokens.forSeat(seatRepository, seatId, fanId, clock)))
                    .isInstanceOfSatisfying(BusinessException.class,
                            exception -> assertThat(exception.errorCode()).isEqualTo(ErrorCode.PAYMENT_FAILED));
            assertThat(ticketRepository.countBySeatId(seatId)).isZero();
            assertThat(seatRepository.findById(seatId).orElseThrow().getStatus()).isEqualTo(SeatStatus.AVAILABLE);
        } finally {
            TestTransaction.start();
        }
    }

    @Test
    void missingIdempotencyKeyFails() {
        Long fanId = createFan();
        Long seatId = createConcertSeat();

        assertThatThrownBy(() -> ticketService.purchase(fanId, new TicketPurchaseRequest(java.util.List.of(seatId)), "", TestEntryTokens.forSeat(seatRepository, seatId, fanId, clock)))
                .isInstanceOf(BusinessException.class);
    }

    @Test
    void sameIdempotencyKeyReturnsPreviousResponse() {
        Long fanId = createFan();
        Long seatId = createConcertSeat();

        var first = ticketService.purchase(fanId, new TicketPurchaseRequest(java.util.List.of(seatId)), "same-key", TestEntryTokens.forSeat(seatRepository, seatId, fanId, clock));
        var second = ticketService.purchase(fanId, new TicketPurchaseRequest(java.util.List.of(seatId)), "same-key", TestEntryTokens.forSeat(seatRepository, seatId, fanId, clock));

        assertThat(second).isEqualTo(first);
    }

    private Long createFan() {
        return userRepository.save(TestUsers.fan("Fan")).getId();
    }

    @TestConfiguration
    static class PaymentConfiguration {
        @Bean
        @Primary
        FailingPaymentClient failingPaymentClient() {
            return new FailingPaymentClient();
        }
    }

    private Long createConcertSeat() {
        User artist = userRepository.save(TestUsers.artist("Artist"));
        var request = new ConcertCreateRequest(
                "Spring Concert",
                "Olympic Park",
                clock.instant().plus(30, ChronoUnit.DAYS),
                clock.instant().minus(1, ChronoUnit.DAYS),
                clock.instant().plus(29, ChronoUnit.DAYS),
                100_000
        );
        Long concertId = concertService.createConcert(artist.getId(), request).concertId();
        return seatRepository.findByConcertId(concertId).getFirst().getId();
    }
}
