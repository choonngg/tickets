package com.project.ticket.domain.seat.service;

import static org.assertj.core.api.Assertions.assertThat;

import com.project.ticket.domain.concert.dto.ConcertCreateRequest;
import com.project.ticket.domain.concert.service.ConcertService;
import com.project.ticket.domain.seat.dto.SeatSectionSummaryResponse;
import com.project.ticket.domain.seat.entity.SeatStatus;
import com.project.ticket.domain.seat.repository.SeatRepository;
import com.project.ticket.domain.user.entity.User;
import com.project.ticket.domain.user.repository.UserRepository;
import java.time.Clock;
import java.time.temporal.ChronoUnit;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.transaction.annotation.Transactional;

@ActiveProfiles("test")
@SpringBootTest
@Transactional
class SeatServiceTest {
    @Autowired Clock clock;

    @Autowired
    SeatService seatService;

    @Autowired
    ConcertService concertService;

    @Autowired
    UserRepository userRepository;

    @Autowired
    SeatRepository seatRepository;

    @Test
    void findsAllSeatsForConcert() {
        Long concertId = createConcert();

        var seats = seatService.findSeats(concertId);

        assertThat(seats).hasSize(15_000);
        assertThat(seats)
                .extracting("section", "row", "col", "price", "status")
                .contains(
                        org.assertj.core.groups.Tuple.tuple("A", 1, 1, 50_000, SeatStatus.AVAILABLE),
                        org.assertj.core.groups.Tuple.tuple("J", 50, 30, 50_000, SeatStatus.AVAILABLE)
                );
    }

    @Test
    void findsOnlyAvailableSeatsForConcert() {
        Long concertId = createConcert();
        var soldSeat = seatRepository.findByConcertId(concertId).getFirst();
        soldSeat.completeSale();

        var seats = seatService.findAvailableSeats(concertId);

        assertThat(seats).hasSize(14_999);
        assertThat(seats).allMatch(seat -> seat.status() == SeatStatus.AVAILABLE);
    }

    @Test
    void findsAvailableSeatCountsBySection() {
        Long concertId = createConcert();
        var seats = seatRepository.findByConcertId(concertId);
        seats.getFirst().completeSale();

        var response = seatService.findAvailabilitySummary(concertId);
        long summedAvailable = response.sections().stream()
                .mapToLong(SeatSectionSummaryResponse::availableCount)
                .sum();

        assertThat(response.totalAvailable()).isEqualTo(14_999);
        assertThat(response.totalAvailable()).isEqualTo(summedAvailable);
        assertThat(response.sections()).hasSize(10);
        assertThat(response.sections())
                .extracting("section", "availableCount")
                .contains(
                        org.assertj.core.groups.Tuple.tuple("A", 1_499L),
                        org.assertj.core.groups.Tuple.tuple("J", 1_500L)
                );
    }

    @Test
    void findsAllSeatsForSelectedSectionWithStatus() {
        Long concertId = createConcert();
        var seats = seatRepository.findByConcertId(concertId);
        seats.getFirst().completeSale();

        var response = seatService.findSectionAvailability(concertId, "A");

        assertThat(response.section()).isEqualTo("A");
        assertThat(response.availableCount()).isEqualTo(1_499);
        assertThat(response.seats()).hasSize(1_500);
        assertThat(response.seats())
                .allSatisfy(seat -> {
                    assertThat(seat.seatId()).isNotNull();
                    assertThat(seat.row()).isPositive();
                    assertThat(seat.col()).isPositive();
                    assertThat(seat.price()).isEqualTo(50_000);
                    assertThat(seat.status()).isNotNull();
                });
        assertThat(response.seats())
                .extracting("status")
                .contains(SeatStatus.SOLD, SeatStatus.AVAILABLE);
    }

    private Long createConcert() {
        User artist = userRepository.save(User.createArtist("Artist"));
        var request = new ConcertCreateRequest(
                "Spring Concert",
                "Olympic Park",
                clock.instant().plus(30, ChronoUnit.DAYS),
                clock.instant().plus(1, ChronoUnit.DAYS),
                clock.instant().plus(29, ChronoUnit.DAYS),
                50_000
        );
        return concertService.createConcert(artist.getId(), request).concertId();
    }
}
