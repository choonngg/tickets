package com.project.ticket.domain.queue.service;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;
import com.project.ticket.domain.concert.entity.Concert;
import com.project.ticket.domain.concert.repository.ConcertRepository;
import com.project.ticket.domain.user.repository.UserRepository;
import com.project.ticket.global.exception.BusinessException;
import com.project.ticket.global.exception.ErrorCode;
import com.project.ticket.infra.queue.QueueRedisRepository;
import com.project.ticket.infra.queue.QueueRedisRepository.State;
import com.project.ticket.support.TestUsers;
import java.time.Clock;
import java.time.Instant;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

class QueueServiceTest {
    private final Instant open = Instant.parse("2030-01-01T00:00:00Z");
    private final UserRepository users = mock(UserRepository.class);
    private final ConcertRepository concerts = mock(ConcertRepository.class);
    private final QueueRedisRepository queue = mock(QueueRedisRepository.class);
    private final EntryTokenProvider tokens = mock(EntryTokenProvider.class);
    private final Clock clock = mock(Clock.class);
    private final QueueService service = new QueueService(queue, concerts, users, tokens, clock);

    @Test void injectedClockEnforcesInclusiveOpenExclusiveCloseAndCancelledConcert() {
        var fan = TestUsers.fan("Fan");
        var concert = Concert.create(TestUsers.artist("Artist"), "Concert", "Venue",
                open.plusSeconds(7200), open, open.plusSeconds(3600));
        when(users.findById(1L)).thenReturn(Optional.of(fan));
        when(concerts.findById(123L)).thenReturn(Optional.of(concert));
        when(queue.register(123, 1)).thenReturn(new QueueRedisRepository.Status(State.WAITING, 1, 0,
                open.plusSeconds(9), 3000, 3000, 0));
        when(clock.instant()).thenReturn(open.minusNanos(1));
        assertError(ErrorCode.CONCERT_NOT_ON_SALE);
        when(clock.instant()).thenReturn(open);
        var registered = service.register(123, 1);
        assertThat(registered.status()).isEqualTo(State.WAITING);
        assertThat(registered.serverTime()).isEqualTo(open);
        when(clock.instant()).thenReturn(open.plusSeconds(3600));
        assertError(ErrorCode.CONCERT_NOT_ON_SALE);
        when(clock.instant()).thenReturn(open);
        ReflectionTestUtils.setField(concert, "cancelledAt", open.minusSeconds(1));
        assertError(ErrorCode.CONCERT_NOT_ON_SALE);
        verify(queue, times(1)).register(123, 1);
    }

    @Test void missingUserAndConcertFailBeforeTouchingRedis() {
        when(users.findById(1L)).thenReturn(Optional.empty());
        assertError(ErrorCode.USER_NOT_FOUND);
        when(users.findById(1L)).thenReturn(Optional.of(TestUsers.fan("Fan")));
        when(concerts.findById(123L)).thenReturn(Optional.empty());
        assertError(ErrorCode.CONCERT_NOT_FOUND);
        verifyNoInteractions(queue, tokens);
    }

    @Test void statusUsesOnlyRedisForEveryStateIncludingMissingRegistration() {
        when(clock.instant()).thenReturn(open);
        for (State state : new State[]{State.WAITING, State.ADMITTED, State.ACTIVE, State.EXPIRED}) {
            var result = new QueueRedisRepository.Status(state, 0, 0, null, 0, 0, 0);
            when(queue.status(123, 1)).thenReturn(result);
            var response = service.status(123, 1);
            assertThat(response.status()).isEqualTo(state);
            assertThat(response.serverTime()).isEqualTo(open);
        }
        verify(queue, times(4)).status(123, 1);
        verifyNoMoreInteractions(queue);
        verifyNoInteractions(users, concerts, tokens);
    }

    @Test void cancellationStillRejectsDeletedUserBeforeTouchingRedis() {
        var fan = TestUsers.fan("Deleted fan");
        fan.delete(open);
        when(users.findById(1L)).thenReturn(Optional.of(fan));
        assertThatThrownBy(() -> service.cancel(123, 1)).isInstanceOfSatisfying(BusinessException.class,
                error -> assertThat(error.errorCode()).isEqualTo(ErrorCode.USER_NOT_FOUND));
        verifyNoInteractions(queue, concerts, tokens);
    }

    private void assertError(ErrorCode code) {
        assertThatThrownBy(() -> service.register(123, 1)).isInstanceOfSatisfying(BusinessException.class,
                error -> assertThat(error.errorCode()).isEqualTo(code));
    }
}
