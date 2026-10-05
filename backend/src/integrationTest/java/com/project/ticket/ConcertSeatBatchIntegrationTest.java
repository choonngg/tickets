package com.project.ticket;

import com.project.ticket.support.TestUsers;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.project.ticket.domain.concert.dto.ConcertCreateRequest;
import com.project.ticket.domain.concert.service.ConcertService;
import com.project.ticket.domain.user.entity.User;
import com.project.ticket.domain.user.repository.UserRepository;
import com.project.ticket.support.IntegrationTestSupport;
import java.time.Clock;
import java.time.temporal.ChronoUnit;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.dao.DataAccessException;
import org.springframework.jdbc.core.JdbcTemplate;

class ConcertSeatBatchIntegrationTest extends IntegrationTestSupport {
    @Autowired ConcertService concerts;
    @Autowired UserRepository users;
    @Autowired JdbcTemplate jdbc;
    @Autowired Clock clock;
    private Long artistId;

    @BeforeEach
    void createArtist() {
        artistId = users.saveAndFlush(TestUsers.artist("Batch Artist")).getId();
    }

    @AfterEach
    void cleanup() {
        jdbc.execute("DROP TRIGGER IF EXISTS fail_seat_batch");
        jdbc.update("DELETE FROM seat WHERE concert_id IN (SELECT id FROM concert WHERE artist_id = ?)", artistId);
        jdbc.update("DELETE FROM concert WHERE artist_id = ?", artistId);
        users.deleteById(artistId);
    }

    @Test
    void commitsConcertAnd15000SeatsAndMeasuresCreationTime() {
        long started = System.nanoTime();
        Long concertId = concerts.createConcert(artistId, request()).concertId();
        double elapsedMs = (System.nanoTime() - started) / 1_000_000.0;
        System.out.printf("MySQL concert + 15000 seats creation: %.3f ms%n", elapsedMs);

        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM concert WHERE id = ?", Long.class, concertId)).isEqualTo(1);
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM seat WHERE concert_id = ?", Long.class, concertId)).isEqualTo(15_000);
        assertThat(jdbc.queryForList("""
                SELECT section_name, COUNT(*) AS seat_count, MIN(row_no) AS first_row,
                       MAX(row_no) AS last_row, MIN(seat_no) AS first_seat, MAX(seat_no) AS last_seat
                FROM seat WHERE concert_id = ? GROUP BY section_name ORDER BY section_name
                """, concertId)).hasSize(10).allSatisfy(section -> {
            assertThat(((Number) section.get("seat_count")).intValue()).isEqualTo(1_500);
            assertThat(((Number) section.get("first_row")).intValue()).isEqualTo(1);
            assertThat(((Number) section.get("last_row")).intValue()).isEqualTo(50);
            assertThat(((Number) section.get("first_seat")).intValue()).isEqualTo(1);
            assertThat(((Number) section.get("last_seat")).intValue()).isEqualTo(30);
        });
        assertThat(jdbc.queryForObject("""
                SELECT COUNT(*) FROM seat WHERE concert_id = ? AND price = 100000
                  AND status = 'AVAILABLE' AND version = 0
                  AND created_at IS NOT NULL AND updated_at IS NOT NULL
                """, Long.class, concertId)).isEqualTo(15_000);
    }

    @Test
    void laterBatchFailureRollsBackConcertAndEarlierSeatBatches() {
        long seatCount = jdbc.queryForObject("SELECT COUNT(*) FROM seat", Long.class);
        // Section B starts after 1500 rows, so at least one 1000-row batch has succeeded.
        jdbc.execute("""
                CREATE TRIGGER fail_seat_batch BEFORE INSERT ON seat FOR EACH ROW
                BEGIN
                  IF NEW.section_name = 'B' THEN
                    SIGNAL SQLSTATE '45000' SET MESSAGE_TEXT = 'injected seat batch failure';
                  END IF;
                END
                """);

        assertThatThrownBy(() -> concerts.createConcert(artistId, request()))
                .isInstanceOf(DataAccessException.class).hasMessageContaining("injected seat batch failure");

        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM concert WHERE artist_id = ?", Long.class, artistId)).isZero();
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM seat", Long.class)).isEqualTo(seatCount);
    }

    private ConcertCreateRequest request() {
        return new ConcertCreateRequest("Batch Concert", "Venue",
                clock.instant().plus(30, ChronoUnit.DAYS),
                clock.instant().plus(1, ChronoUnit.DAYS),
                clock.instant().plus(29, ChronoUnit.DAYS), 100_000);
    }
}
