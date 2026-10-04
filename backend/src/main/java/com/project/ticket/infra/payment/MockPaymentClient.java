package com.project.ticket.infra.payment;

import com.project.ticket.domain.seat.entity.Seat;
import com.project.ticket.domain.user.entity.User;
import org.springframework.stereotype.Component;

@Component
public class MockPaymentClient implements PaymentClient {
    @Override
    public PaymentResult pay(User user, Seat seat, int amount) {
        return PaymentResult.success();
    }
}
