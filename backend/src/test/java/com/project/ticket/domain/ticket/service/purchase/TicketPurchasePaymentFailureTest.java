package com.project.ticket.domain.ticket.service.purchase;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.spy;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import com.project.ticket.domain.concert.entity.Concert;
import com.project.ticket.domain.seat.entity.Seat;
import com.project.ticket.domain.ticket.repository.TicketRepository;
import com.project.ticket.domain.user.entity.User;
import com.project.ticket.domain.user.repository.UserRepository;
import com.project.ticket.global.exception.BusinessException;
import com.project.ticket.global.exception.ErrorCode;
import com.project.ticket.infra.payment.MockPaymentClient;
import com.project.ticket.infra.payment.PaymentClient;
import com.project.ticket.infra.payment.PaymentResult;
import java.lang.reflect.Modifier;
import java.time.Clock;
import java.time.LocalDateTime;
import java.util.Optional;
import org.junit.jupiter.api.Test;

class TicketPurchasePaymentFailureTest {
    @Test
    void failedPaymentLeavesSeatRollbackToTransactionAndDoesNotCreateTicket() {
        Clock clock = Clock.systemUTC();
        LocalDateTime now = LocalDateTime.now(clock);
        User user = User.createFan("Fan");
        Concert concert = Concert.create(User.createArtist("Artist"), "Concert", "Venue",
                now.plusDays(2), now.minusDays(1), now.plusDays(1));
        Seat seat = spy(Seat.create(concert, "A", 1, 1, 10_000));
        UserRepository users = mock(UserRepository.class);
        TicketRepository tickets = mock(TicketRepository.class);
        PaymentClient payment = mock(PaymentClient.class);
        when(users.findById(1L)).thenReturn(Optional.of(user));
        when(payment.pay(user, seat, seat.getPrice())).thenReturn(PaymentResult.failure(null));
        var processor = new TicketPurchaseProcessor(tickets, users, payment, clock);

        assertThatThrownBy(() -> processor.purchase(1L, 2L, ignored -> Optional.of(seat)))
                .isInstanceOfSatisfying(BusinessException.class,
                        exception -> assertThat(exception.errorCode()).isEqualTo(ErrorCode.PAYMENT_FAILED));

        verify(seat, never()).release();
        verifyNoInteractions(tickets);
    }

    @Test
    void productionMockHasNoMutableTestHooks() {
        assertThat(MockPaymentClient.class.getDeclaredFields())
                .allMatch(field -> Modifier.isFinal(field.getModifiers()));
        assertThat(MockPaymentClient.class.getDeclaredMethods())
                .extracting(java.lang.reflect.Method::getName)
                .doesNotContain("failNextPayment");
    }
}
