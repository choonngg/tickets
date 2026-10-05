package com.project.ticket.domain.concert.entity;

import com.project.ticket.support.TestUsers;

import static org.assertj.core.api.Assertions.assertThat;

import com.project.ticket.domain.concert.dto.ConcertResponse;
import com.project.ticket.domain.user.entity.User;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.time.temporal.ChronoUnit;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

class ConcertStatusTest {
    @Test
    void calculatesStatusAtSaleBoundariesAndCancellationOverridesTime() {
        Clock clock = Clock.fixed(Instant.parse("2026-10-04T03:00:00Z"), ZoneOffset.UTC);
        Instant open = clock.instant();
        Instant close = open.plus(1, ChronoUnit.DAYS);
        Concert concert = Concert.create(TestUsers.artist("Artist"), "Concert", "Venue",
                close.plus(1, ChronoUnit.DAYS), open, close);

        assertThat(concert.statusAt(open.minusNanos(1))).isEqualTo(ConcertStatus.BEFORE_OPEN);
        assertThat(concert.statusAt(open)).isEqualTo(ConcertStatus.ON_SALE);
        assertThat(concert.statusAt(close.minusNanos(1))).isEqualTo(ConcertStatus.ON_SALE);
        assertThat(concert.statusAt(close)).isEqualTo(ConcertStatus.CLOSED);
        assertThat(concert.statusAt(close.plusNanos(1))).isEqualTo(ConcertStatus.CLOSED);
        assertThat(ConcertResponse.from(concert, clock).status()).isEqualTo(ConcertStatus.ON_SALE);

        ReflectionTestUtils.setField(concert, "cancelledAt", open);
        assertThat(concert.statusAt(open.minus(1, ChronoUnit.DAYS))).isEqualTo(ConcertStatus.CANCELLED);
        assertThat(concert.statusAt(open)).isEqualTo(ConcertStatus.CANCELLED);
        assertThat(concert.statusAt(close)).isEqualTo(ConcertStatus.CANCELLED);
        assertThat(concert.isOnSale(open)).isFalse();
        assertThat(ConcertResponse.from(concert, clock).status()).isEqualTo(ConcertStatus.CANCELLED);
    }
}
