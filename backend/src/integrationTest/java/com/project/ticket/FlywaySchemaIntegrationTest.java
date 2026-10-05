package com.project.ticket;

import com.project.ticket.support.TestUsers;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.project.ticket.domain.concert.entity.Concert;
import com.project.ticket.domain.concert.repository.ConcertRepository;
import com.project.ticket.domain.seat.entity.Seat;
import com.project.ticket.domain.seat.entity.SeatStatus;
import com.project.ticket.domain.seat.repository.SeatRepository;
import com.project.ticket.domain.ticket.entity.Ticket;
import com.project.ticket.domain.ticket.repository.TicketRepository;
import com.project.ticket.domain.ticket.service.purchase.PurchaseStrategy;
import com.project.ticket.domain.ticket.service.purchase.TicketPurchaseProperties;
import com.project.ticket.domain.ticket.service.purchase.TicketPurchaseStrategyRouter;
import com.project.ticket.domain.user.entity.User;
import com.project.ticket.domain.user.repository.UserRepository;
import com.project.ticket.global.exception.BusinessException;
import com.project.ticket.global.exception.ErrorCode;
import com.project.ticket.support.IntegrationTestSupport;
import jakarta.persistence.EntityManager;
import java.time.Clock;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.TimeZone;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.core.env.Environment;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.UncategorizedSQLException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.transaction.annotation.Transactional;

class FlywaySchemaIntegrationTest extends IntegrationTestSupport {
    @Autowired Flyway flyway;
    @Autowired Environment environment;
    @Autowired JdbcTemplate jdbc;
    @Autowired Clock clock;
    @Autowired UserRepository users;
    @Autowired ConcertRepository concerts;
    @Autowired SeatRepository seats;
    @Autowired TicketRepository tickets;
    @Autowired TicketPurchaseProperties properties;
    @Autowired TicketPurchaseStrategyRouter router;
    @Autowired EntityManager entityManager;

    @AfterEach
    void resetStrategy() {
        properties.setPurchaseStrategy(PurchaseStrategy.DISTRIBUTED);
    }

    @Test
    void startsWithOnlyV1AndHibernateValidation() {
        assertThat(environment.getProperty("spring.jpa.hibernate.ddl-auto")).isEqualTo("validate");
        assertThat(flyway.getConfiguration().isBaselineOnMigrate()).isFalse();
        assertThat(flyway.info().applied()).hasSize(1);
        assertThat(flyway.info().applied()[0].getScript()).isEqualTo("V1__init.sql");
        flyway.validate();
        assertThat(jdbc.queryForList("""
                SELECT table_name FROM information_schema.tables
                WHERE table_schema = DATABASE()
                """, String.class)).containsExactlyInAnyOrder(
                "users", "auth_session", "refresh_token", "concert", "seat", "ticket", "flyway_schema_history");
    }

    @Test
    void hasOnlyInitialIndexesAndTicketWindowCheck() {
        assertIndexes("concert", "PRIMARY", "ix_concert_artist_fk");
        assertIndexes("seat", "PRIMARY", "uq_seat_position");
        assertIndexes("ticket", "PRIMARY", "uq_ticket_seat", "ix_ticket_user_fk");
        assertIndexes("users", "PRIMARY", "uq_users_google_sub");
        assertIndexes("auth_session", "PRIMARY", "ix_auth_session_user_fk", "ix_auth_session_expires_at");
        assertIndexes("refresh_token", "PRIMARY", "uq_refresh_token_hash", "uq_refresh_token_unconsumed_session", "ix_refresh_token_session_fk");
        assertUniqueIndex("users", "uq_users_google_sub", "google_sub");
        assertUniqueIndex("refresh_token", "uq_refresh_token_hash", "token_hash");
        assertUniqueIndex("refresh_token", "uq_refresh_token_unconsumed_session", "unconsumed_session_id");
        assertUniqueIndex("seat", "uq_seat_position", "concert_id", "section_name", "row_no", "seat_no");
        assertUniqueIndex("ticket", "uq_ticket_seat", "seat_id");
        assertThat(jdbc.queryForList("""
                SELECT constraint_name FROM information_schema.table_constraints
                WHERE table_schema = DATABASE() AND table_name = 'concert' AND constraint_type = 'CHECK'
                """, String.class)).containsExactly("ck_concert_ticket_window");
    }

    @Test
    @Transactional
    void googleIdentityAndOneUnconsumedRefreshTokenAreEnforcedByMysql() {
        jdbc.update("INSERT INTO users (google_sub, email, name) VALUES ('schema-sub', 'shared@example.com', 'Fan')");
        Long userId = jdbc.queryForObject("SELECT id FROM users WHERE google_sub = 'schema-sub'", Long.class);
        assertThatThrownBy(() -> jdbc.update("INSERT INTO users (google_sub, email, name) VALUES ('schema-sub', 'other@example.com', 'Other')"))
                .isInstanceOf(DataIntegrityViolationException.class);
        jdbc.update("INSERT INTO users (google_sub, email, name) VALUES ('SCHEMA-SUB', 'shared@example.com', 'Other')");
        assertThat(jdbc.queryForObject("""
                SELECT collation_name FROM information_schema.columns
                WHERE table_schema = DATABASE() AND table_name = 'users' AND column_name = 'google_sub'
                """, String.class)).isEqualTo("ascii_bin");
        assertThat(jdbc.queryForObject("""
                SELECT column_type FROM information_schema.columns
                WHERE table_schema = DATABASE() AND table_name = 'refresh_token' AND column_name = 'token_hash'
                """, String.class)).isEqualTo("binary(32)");
        jdbc.update("INSERT INTO auth_session (user_id, expires_at) VALUES (?, CURRENT_TIMESTAMP(6) + INTERVAL 14 DAY)", userId);
        Long sessionId = jdbc.queryForObject("SELECT id FROM auth_session WHERE user_id = ?", Long.class, userId);
        jdbc.update("INSERT INTO refresh_token (session_id, token_hash) VALUES (?, UNHEX(SHA2('first', 256)))", sessionId);
        assertThatThrownBy(() -> jdbc.update("INSERT INTO refresh_token (session_id, token_hash) VALUES (?, UNHEX(SHA2('second', 256)))", sessionId))
                .isInstanceOf(DataIntegrityViolationException.class);
        jdbc.update("UPDATE refresh_token SET consumed_at = CURRENT_TIMESTAMP(6) WHERE session_id = ?", sessionId);
        jdbc.update("INSERT INTO refresh_token (session_id, token_hash) VALUES (?, UNHEX(SHA2('second', 256)))", sessionId);
        jdbc.update("INSERT INTO refresh_token (session_id, token_hash, consumed_at) VALUES (?, UNHEX(SHA2('third', 256)), CURRENT_TIMESTAMP(6))", sessionId);
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM refresh_token WHERE session_id = ? AND consumed_at IS NULL", Long.class, sessionId))
                .isEqualTo(1);
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM refresh_token WHERE session_id = ? AND unconsumed_session_id IS NULL", Long.class, sessionId))
                .isEqualTo(2);
        jdbc.update("DELETE FROM auth_session WHERE id = ?", sessionId);
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM refresh_token WHERE session_id = ?", Long.class, sessionId)).isZero();
    }

    @Test
    @Transactional
    void databaseRejectsInvalidWindowDuplicatePositionAndDuplicateTicket() {
        User artist = users.saveAndFlush(TestUsers.artist("Schema Artist"));
        User fan = users.saveAndFlush(TestUsers.fan("Schema Fan"));
        Instant open = clock.instant();
        Instant close = open.plus(1, ChronoUnit.DAYS);
        Concert concert = concerts.saveAndFlush(Concert.create(artist, "Schema Concert", "Venue",
                close, open, close));
        Seat seat = seats.saveAndFlush(Seat.create(concert, "A", 1, 1, 10_000));
        assertThatThrownBy(() -> jdbc.update("UPDATE concert SET ticket_open_at = ticket_close_at WHERE id = ?",
                concert.getId())).isInstanceOf(UncategorizedSQLException.class)
                .hasMessageContaining("ck_concert_ticket_window");
        assertThatThrownBy(() -> jdbc.update("UPDATE concert SET concert_at = ticket_close_at - INTERVAL 1 SECOND WHERE id = ?",
                concert.getId())).isInstanceOf(UncategorizedSQLException.class)
                .hasMessageContaining("ck_concert_ticket_window");
        assertThatThrownBy(() -> jdbc.update("""
                INSERT INTO seat (concert_id, section_name, row_no, seat_no, price) VALUES (?, 'A', 1, 1, 10000)
                """, concert.getId())).isInstanceOf(DataIntegrityViolationException.class);
        jdbc.update("INSERT INTO ticket (user_id, seat_id, purchase_price) VALUES (?, ?, ?)",
                fan.getId(), seat.getId(), 10_000);
        assertThatThrownBy(() -> jdbc.update("INSERT INTO ticket (user_id, seat_id, purchase_price) VALUES (?, ?, ?)",
                fan.getId(), seat.getId(), 10_000)).isInstanceOf(DataIntegrityViolationException.class);
    }

    @ParameterizedTest
    @EnumSource(PurchaseStrategy.class)
    @Transactional
    void cancelledAtRejectsPurchaseForEveryStrategy(PurchaseStrategy strategy) {
        properties.setPurchaseStrategy(strategy);
        User artist = users.save(TestUsers.artist("Cancelled Artist"));
        User fan = users.save(TestUsers.fan("Cancelled Fan"));
        Instant now = clock.instant();
        Concert concert = Concert.create(artist, "Cancelled Concert", "Venue",
                now.plus(2, ChronoUnit.DAYS), now.minus(1, ChronoUnit.DAYS), now.plus(1, ChronoUnit.DAYS));
        ReflectionTestUtils.setField(concert, "cancelledAt", now);
        concerts.saveAndFlush(concert);
        Seat seat = seats.saveAndFlush(Seat.create(concert, "A", 1, 1, 10_000));

        assertThatThrownBy(() -> router.current().purchase(fan.getId(), seat.getId()))
                .isInstanceOfSatisfying(BusinessException.class,
                        exception -> assertThat(exception.errorCode()).isEqualTo(ErrorCode.CONCERT_NOT_ON_SALE));
        assertThat(tickets.countBySeatId(seat.getId())).isZero();
        assertThat(seat.getStatus()).isEqualTo(SeatStatus.AVAILABLE);
    }

    @ParameterizedTest
    @ValueSource(strings = {"UTC", "Asia/Seoul"})
    @Transactional
    void instantsRoundTripAsUtcRegardlessOfJvmDefault(String zone) {
        TimeZone original = TimeZone.getDefault();
        try {
            TimeZone.setDefault(TimeZone.getTimeZone(zone));
            assertThat(environment.getProperty("spring.jpa.properties.hibernate.jdbc.time_zone")).isEqualTo("UTC");
            assertThat(jdbc.queryForObject("SELECT @@session.time_zone", String.class)).isEqualTo("+00:00");
            Instant open = Instant.parse("2026-10-04T03:00:00.123456Z");
            Instant close = open.plus(1, ChronoUnit.DAYS);
            Instant concertAt = close.plus(1, ChronoUnit.DAYS);
            Instant cancelledAt = open.plusSeconds(1);
            Instant purchasedAt = open.plusSeconds(2);
            Instant beforeInsert = databaseNow();
            User artist = users.saveAndFlush(TestUsers.artist("UTC Artist"));
            User fan = users.saveAndFlush(TestUsers.fan("UTC Fan"));
            Concert concert = Concert.create(artist, "UTC Concert", "Venue", concertAt, open, close);
            ReflectionTestUtils.setField(concert, "cancelledAt", cancelledAt);
            concerts.saveAndFlush(concert);
            Seat seat = seats.saveAndFlush(Seat.create(concert, "A", 1, 1, 10_000));
            Ticket ticket = tickets.saveAndFlush(Ticket.complete(fan, seat, 10_000, purchasedAt));
            Long concertId = concert.getId();
            Long seatId = seat.getId();
            Long ticketId = ticket.getId();
            entityManager.clear();

            Concert loadedConcert = concerts.findById(concertId).orElseThrow();
            Seat loadedSeat = seats.findById(seatId).orElseThrow();
            Ticket loadedTicket = tickets.findById(ticketId).orElseThrow();
            Instant afterInsert = databaseNow();
            assertThat(loadedConcert.getConcertDate()).isEqualTo(concertAt);
            assertThat(loadedConcert.getTicketOpenDate()).isEqualTo(open);
            assertThat(loadedConcert.getTicketCloseDate()).isEqualTo(close);
            assertThat(loadedConcert.getCancelledAt()).isEqualTo(cancelledAt);
            assertThat(loadedTicket.getCreatedAt()).isEqualTo(purchasedAt);
            assertThat(loadedConcert.getCreatedAt()).isBetween(beforeInsert, afterInsert);
            assertThat(loadedConcert.getUpdatedAt()).isBetween(beforeInsert, afterInsert);
            assertThat(loadedSeat.getCreatedAt()).isBetween(beforeInsert, afterInsert);
            assertThat(loadedSeat.getUpdatedAt()).isBetween(beforeInsert, afterInsert);
            assertThat(jdbc.queryForObject("SELECT DATE_FORMAT(concert_at, '%Y-%m-%dT%H:%i:%s.%fZ') FROM concert WHERE id = ?",
                    String.class, concertId)).isEqualTo(concertAt.toString());
            assertThat(jdbc.queryForObject("SELECT DATE_FORMAT(created_at, '%Y-%m-%dT%H:%i:%s.%fZ') FROM ticket WHERE id = ?",
                    String.class, ticketId)).isEqualTo(purchasedAt.toString());
        } finally {
            TimeZone.setDefault(original);
        }
    }

    private Instant databaseNow() {
        return Instant.parse(jdbc.queryForObject("SELECT DATE_FORMAT(UTC_TIMESTAMP(6), '%Y-%m-%dT%H:%i:%s.%fZ')", String.class));
    }

    private void assertIndexes(String table, String... names) {
        assertThat(jdbc.queryForList("SHOW INDEX FROM " + table).stream()
                .map(row -> (String) row.get("Key_name")).distinct().toList())
                .containsExactlyInAnyOrder(names);
    }

    private void assertUniqueIndex(String table, String name, String... columns) {
        List<String> actual = jdbc.queryForList("SHOW INDEX FROM " + table).stream()
                .filter(row -> name.equals(row.get("Key_name")))
                .sorted(java.util.Comparator.comparingInt(row -> ((Number) row.get("Seq_in_index")).intValue()))
                .map(row -> {
                    assertThat(((Number) row.get("Non_unique")).intValue()).isZero();
                    return (String) row.get("Column_name");
                }).toList();
        assertThat(actual).containsExactly(columns);
    }
}
