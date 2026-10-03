package com.project.ticket.domain.ticket.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.project.ticket.domain.concert.dto.ConcertCreateRequest;
import com.project.ticket.domain.concert.service.ConcertService;
import com.project.ticket.domain.payment.entity.PaymentStatus;
import com.project.ticket.domain.payment.repository.PaymentRepository;
import com.project.ticket.domain.seat.entity.SeatStatus;
import com.project.ticket.domain.seat.repository.SeatRepository;
import com.project.ticket.domain.ticket.dto.TicketPurchaseRequest;
import com.project.ticket.domain.ticket.entity.TicketStatus;
import com.project.ticket.domain.ticket.repository.TicketRepository;
import com.project.ticket.domain.ticket.service.purchase.PurchaseStrategy;
import com.project.ticket.domain.ticket.service.purchase.TicketPurchaseProperties;
import com.project.ticket.domain.user.entity.User;
import com.project.ticket.domain.user.repository.UserRepository;
import com.project.ticket.global.exception.BusinessException;
import com.project.ticket.global.exception.ErrorCode;
import com.project.ticket.support.FailingPaymentClient;
import java.time.LocalDateTime;
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
import org.springframework.transaction.annotation.Transactional;

@ActiveProfiles("test")
@SpringBootTest
@Transactional
@Import(TicketServiceTest.PaymentConfiguration.class)
class TicketServiceTest {

    @Autowired
    TicketService ticketService;

    @Autowired
    ConcertService concertService;

    @Autowired
    UserRepository userRepository;

    @Autowired
    SeatRepository seatRepository;

    @Autowired
    PaymentRepository paymentRepository;

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

        var response = ticketService.purchase(fanId, new TicketPurchaseRequest(seatId), "key-1");

        assertThat(response.status()).isEqualTo(TicketStatus.COMPLETED);
        assertThat(response.seatId()).isEqualTo(seatId);
        assertThat(response.paymentId()).isPositive();
        var payment = paymentRepository.findById(response.paymentId()).orElseThrow();
        assertThat(payment.getStatus()).isEqualTo(PaymentStatus.SUCCEEDED);
        assertThat(payment.getUser().getId()).isEqualTo(fanId);
        assertThat(payment.getSeat().getId()).isEqualTo(seatId);
        assertThat(seatRepository.findById(seatId).orElseThrow().getStatus()).isEqualTo(SeatStatus.SOLD);
    }

    @ParameterizedTest
    @EnumSource(PurchaseStrategy.class)
    void paymentFailureRollsBackWithoutTicketOrPayment(PurchaseStrategy strategy) {
        properties.setPurchaseStrategy(strategy);
        Long fanId = createFan();
        Long seatId = createConcertSeat();
        TestTransaction.flagForCommit();
        TestTransaction.end();
        long paymentCount = paymentRepository.count();
        mockPaymentClient.failNextPayment();

        try {
            assertThatThrownBy(() -> ticketService.purchase(fanId, new TicketPurchaseRequest(seatId), "key-2"))
                    .isInstanceOfSatisfying(BusinessException.class,
                            exception -> assertThat(exception.errorCode()).isEqualTo(ErrorCode.PAYMENT_FAILED));
            assertThat(ticketRepository.countBySeatId(seatId)).isZero();
            assertThat(paymentRepository.count()).isEqualTo(paymentCount);
            assertThat(seatRepository.findById(seatId).orElseThrow().getStatus()).isEqualTo(SeatStatus.AVAILABLE);
        } finally {
            TestTransaction.start();
        }
    }

    @Test
    void missingIdempotencyKeyFails() {
        Long fanId = createFan();
        Long seatId = createConcertSeat();

        assertThatThrownBy(() -> ticketService.purchase(fanId, new TicketPurchaseRequest(seatId), ""))
                .isInstanceOf(BusinessException.class);
    }

    @Test
    void sameIdempotencyKeyReturnsPreviousResponse() {
        Long fanId = createFan();
        Long seatId = createConcertSeat();

        var first = ticketService.purchase(fanId, new TicketPurchaseRequest(seatId), "same-key");
        var second = ticketService.purchase(fanId, new TicketPurchaseRequest(seatId), "same-key");

        assertThat(second).isEqualTo(first);
    }

    private Long createFan() {
        return userRepository.save(User.createFan("Fan")).getId();
    }

    @TestConfiguration
    static class PaymentConfiguration {
        @Bean
        @Primary
        FailingPaymentClient failingPaymentClient(PaymentRepository payments) {
            return new FailingPaymentClient(payments);
        }
    }

    private Long createConcertSeat() {
        User artist = userRepository.save(User.createArtist("Artist"));
        var request = new ConcertCreateRequest(
                "Spring Concert",
                "Olympic Park",
                LocalDateTime.now().plusDays(30),
                LocalDateTime.now().minusDays(1),
                LocalDateTime.now().plusDays(29),
                100_000
        );
        Long concertId = concertService.createConcert(artist.getId(), request).concertId();
        return seatRepository.findByConcertId(concertId).getFirst().getId();
    }
}
