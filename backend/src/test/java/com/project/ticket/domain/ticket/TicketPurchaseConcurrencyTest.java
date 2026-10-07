package com.project.ticket.domain.ticket;

import com.project.ticket.support.TestUsers;
import com.project.ticket.support.TestEntryTokens;

import static org.assertj.core.api.Assertions.assertThat;

import com.project.ticket.domain.concert.dto.ConcertCreateRequest;
import com.project.ticket.domain.concert.service.ConcertService;
import com.project.ticket.domain.seat.entity.SeatStatus;
import com.project.ticket.domain.seat.repository.SeatRepository;
import com.project.ticket.domain.ticket.dto.TicketPurchaseRequest;
import com.project.ticket.domain.ticket.repository.TicketRepository;
import com.project.ticket.domain.ticket.service.TicketService;
import com.project.ticket.domain.ticket.service.purchase.PurchaseStrategy;
import com.project.ticket.domain.ticket.service.purchase.TicketPurchaseProperties;
import com.project.ticket.domain.user.entity.User;
import com.project.ticket.domain.user.repository.UserRepository;
import com.project.ticket.global.exception.BusinessException;
import com.project.ticket.global.exception.ErrorCode;
import java.time.Clock;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.stream.IntStream;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;

@ActiveProfiles("test")
@SpringBootTest
class TicketPurchaseConcurrencyTest {
    @Autowired Clock clock;
    private static final int REQUEST_COUNT = 50;

    @Autowired
    TicketService ticketService;

    @Autowired
    TicketPurchaseProperties properties;

    @Autowired
    ConcertService concertService;

    @Autowired
    UserRepository userRepository;

    @Autowired
    SeatRepository seatRepository;

    @Autowired
    TicketRepository ticketRepository;

    @AfterEach
    void resetStrategy() {
        properties.setPurchaseStrategy(PurchaseStrategy.DISTRIBUTED);
    }

    @ParameterizedTest
    @EnumSource(PurchaseStrategy.class)
    void sameSeatConcurrentPurchaseCreatesOnlyOneTicket(PurchaseStrategy strategy) throws Exception {
        properties.setPurchaseStrategy(strategy);
        Long fanId = createFan();
        Long seatId = createConcertSeat();

        List<Long> ticketIds = executeConcurrentPurchases(Collections.nCopies(REQUEST_COUNT, fanId), seatId, true);

        assertThat(ticketRepository.countBySeatId(seatId)).isEqualTo(1);
        // Different keys do not replay a consumed admission.
        assertThat(ticketIds).hasSize(1)
                .containsOnly(ticketRepository.findBySeatId(seatId).orElseThrow().getId());
    }

    @ParameterizedTest
    @EnumSource(PurchaseStrategy.class)
    void fiftyDifferentUsersBuyingSameSeatCreateExactlyOneTicket(PurchaseStrategy strategy) throws Exception {
        properties.setPurchaseStrategy(strategy);
        Long seatId = createConcertSeat();
        var fans = IntStream.range(0, REQUEST_COUNT).mapToObj(ignored -> createFan()).toList();

        assertThat(executeConcurrentPurchases(fans, seatId, false)).hasSize(1);
        assertThat(ticketRepository.countBySeatId(seatId)).isEqualTo(1);
        assertThat(seatRepository.findById(seatId).orElseThrow().getStatus()).isEqualTo(SeatStatus.SOLD);
    }

    private List<Long> executeConcurrentPurchases(List<Long> fanIds, Long seatId, boolean sameUser) throws Exception {
        CountDownLatch ready = new CountDownLatch(REQUEST_COUNT);
        CountDownLatch start = new CountDownLatch(1);
        ExecutorService executor = Executors.newFixedThreadPool(REQUEST_COUNT);
        try {
            List<Future<Long>> results = IntStream.range(0, REQUEST_COUNT)
                    .mapToObj(index -> executor.submit(() -> {
                        ready.countDown();
                        start.await();
                        try {
                            return ticketService.purchase(fanIds.get(index), new TicketPurchaseRequest(java.util.List.of(seatId)), "key-" + index, TestEntryTokens.forSeat(seatRepository, seatId, fanIds.get(index), clock))
                                    .getFirst().ticketId();
                        } catch (BusinessException exception) {
                            if (sameUser) {
                                // One-shot token/claim per user: concurrent same-user requests may also lose the claim.
                                assertThat(exception.errorCode()).isIn(ErrorCode.SEAT_NOT_AVAILABLE, ErrorCode.SEAT_LOCK_FAILED, ErrorCode.REQUEST_IN_PROGRESS, ErrorCode.ENTRY_TOKEN_INVALID);
                                assertThat(exception.errorCode().status().value()).isIn(403, 409);
                            } else {
                                // Each user holds an independent valid token: only seat contention may fail.
                                assertThat(exception.errorCode()).isIn(ErrorCode.SEAT_NOT_AVAILABLE, ErrorCode.SEAT_LOCK_FAILED);
                                assertThat(exception.errorCode().status().value()).isEqualTo(409);
                            }
                            return null;
                        }
                    }))
                    .toList();

            assertThat(ready.await(10, TimeUnit.SECONDS)).isTrue();
            start.countDown();

            List<Long> ticketIds = new ArrayList<>();
            for (Future<Long> result : results) {
                Long ticketId = result.get(10, TimeUnit.SECONDS);
                if (ticketId != null) {
                    ticketIds.add(ticketId);
                }
            }
            return ticketIds;
        } finally {
            executor.shutdownNow();
        }
    }

    private Long createFan() {
        return userRepository.save(TestUsers.fan("Fan")).getId();
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
