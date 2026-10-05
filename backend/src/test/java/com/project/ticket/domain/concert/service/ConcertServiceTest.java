package com.project.ticket.domain.concert.service;

import com.project.ticket.support.TestUsers;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.verify;

import com.project.ticket.domain.concert.dto.ConcertCreateRequest;
import com.project.ticket.domain.seat.entity.Seat;
import com.project.ticket.domain.seat.repository.SeatRepository;
import com.project.ticket.domain.user.entity.User;
import com.project.ticket.domain.user.repository.UserRepository;
import java.time.Clock;
import java.time.temporal.ChronoUnit;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

@ActiveProfiles("test")
@SpringBootTest(properties = "ticket.seat-batch-size=512")
@Transactional
class ConcertServiceTest {
    @Autowired Clock clock;

    @Autowired
    ConcertService concertService;

    @Autowired
    UserRepository userRepository;

    @MockitoSpyBean
    SeatRepository seatRepository;

    @MockitoSpyBean
    JdbcTemplate jdbcTemplate;

    @Test
    void withdrawnArtistCannotCreateConcert() {
        User artist = TestUsers.artist("Artist");
        artist.delete(clock.instant());
        userRepository.save(artist);
        var request = new ConcertCreateRequest("T", "V", clock.instant().plus(30, ChronoUnit.DAYS),
                clock.instant().plus(1, ChronoUnit.DAYS), clock.instant().plus(29, ChronoUnit.DAYS), 100_000);
        assertThatThrownBy(() -> concertService.createConcert(artist.getId(), request))
                .isInstanceOf(com.project.ticket.global.exception.BusinessException.class)
                .extracting(e -> ((com.project.ticket.global.exception.BusinessException) e).errorCode())
                .isEqualTo(com.project.ticket.global.exception.ErrorCode.USER_NOT_FOUND);
    }

    @Test
    void artistCreatesConcertWithDefaultSeatLayout() {
        User artist = userRepository.save(TestUsers.artist("Artist"));
        var request = new ConcertCreateRequest(
                "Spring Concert",
                "Olympic Park",
                clock.instant().plus(30, ChronoUnit.DAYS),
                clock.instant().plus(1, ChronoUnit.DAYS),
                clock.instant().plus(29, ChronoUnit.DAYS),
                100_000
        );

        long started = System.nanoTime();
        var response = concertService.createConcert(artist.getId(), request);
        System.out.printf("H2 concert + 15000 seats creation (test transaction): %.3f ms%n",
                (System.nanoTime() - started) / 1_000_000.0);
        List<Seat> seats = seatRepository.findByConcertId(response.concertId());

        assertThat(response.title()).isEqualTo("Spring Concert");
        verify(jdbcTemplate).batchUpdate(anyString(),
                argThat((List<Seat> batch) -> batch.size() == 15_000), eq(512), any());
        verify(seatRepository, never()).saveAll(any());
        verify(seatRepository, never()).save(any());
        assertThat(seats).hasSize(15_000);
        assertThat(seats).allSatisfy(seat -> {
            assertThat(seat.getPrice()).isEqualTo(100_000);
            assertThat(seat.getStatus()).isEqualTo(com.project.ticket.domain.seat.entity.SeatStatus.AVAILABLE);
            assertThat(seat.getVersion()).isZero();
            assertThat(seat.getCreatedAt()).isNotNull();
            assertThat(seat.getUpdatedAt()).isNotNull();
        });
        assertThat(seats)
                .extracting(Seat::getSection)
                .containsOnly("A", "B", "C", "D", "E", "F", "G", "H", "I", "J");
        assertThat(seats)
                .filteredOn(seat -> seat.getSection().equals("A"))
                .hasSize(1_500)
                .extracting(Seat::getRow, Seat::getCol)
                .contains(
                        org.assertj.core.groups.Tuple.tuple(1, 1),
                        org.assertj.core.groups.Tuple.tuple(50, 30)
                );
    }

    @Test
    @Transactional(propagation = Propagation.NOT_SUPPORTED)
    void jdbcFailureRollsBackConcertAndInsertedSeats() {
        User artist = userRepository.saveAndFlush(TestUsers.artist("Rollback Artist"));
        long seatCount = jdbcTemplate.queryForObject("SELECT COUNT(*) FROM seat", Long.class);
        var request = new ConcertCreateRequest("Rollback Concert", "Venue",
                clock.instant().plus(30, ChronoUnit.DAYS),
                clock.instant().plus(1, ChronoUnit.DAYS),
                clock.instant().plus(29, ChronoUnit.DAYS), 100_000);
        doAnswer(invocation -> {
            invocation.callRealMethod();
            throw new DataIntegrityViolationException("injected batch failure after inserts");
        }).when(jdbcTemplate).batchUpdate(anyString(), any(List.class), eq(512), any());

        try {
            assertThatThrownBy(() -> concertService.createConcert(artist.getId(), request))
                    .isInstanceOf(DataIntegrityViolationException.class);
            assertThat(jdbcTemplate.queryForObject("SELECT COUNT(*) FROM concert WHERE artist_id = ?",
                    Long.class, artist.getId())).isZero();
            assertThat(jdbcTemplate.queryForObject("SELECT COUNT(*) FROM seat", Long.class)).isEqualTo(seatCount);
        } finally {
            userRepository.deleteById(artist.getId());
        }
    }
}
