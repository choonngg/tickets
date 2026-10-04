package com.project.ticket.infra.payment;

import com.project.ticket.domain.seat.entity.Seat;
import com.project.ticket.domain.user.entity.User;

public interface PaymentClient {
    PaymentResult pay(User user, Seat seat, int amount);
}
