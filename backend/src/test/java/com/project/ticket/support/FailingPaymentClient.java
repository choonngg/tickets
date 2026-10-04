package com.project.ticket.support;

import com.project.ticket.domain.seat.entity.Seat;
import com.project.ticket.domain.user.entity.User;
import com.project.ticket.infra.payment.MockPaymentClient;
import com.project.ticket.infra.payment.PaymentResult;
import java.util.concurrent.atomic.AtomicBoolean;

public class FailingPaymentClient extends MockPaymentClient {
    private final AtomicBoolean failNext = new AtomicBoolean();

    public void failNextPayment() {
        failNext.set(true);
    }

    @Override
    public PaymentResult pay(User user, Seat seat, int amount) {
        return failNext.getAndSet(false) ? PaymentResult.failure() : super.pay(user, seat, amount);
    }
}
