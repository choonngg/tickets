package com.project.ticket.infra.payment;

import com.project.ticket.domain.payment.entity.Payment;
import com.project.ticket.domain.payment.repository.PaymentRepository;
import com.project.ticket.domain.seat.entity.Seat;
import com.project.ticket.domain.user.entity.User;
import org.springframework.stereotype.Component;

@Component
public class MockPaymentClient implements PaymentClient {
    private final PaymentRepository paymentRepository;

    public MockPaymentClient(PaymentRepository paymentRepository) {
        this.paymentRepository = paymentRepository;
    }

    @Override
    public PaymentResult pay(User user, Seat seat, int amount) {
        Payment payment = paymentRepository.save(Payment.succeeded(user, seat, amount));
        return PaymentResult.success(payment);
    }

    @Override
    public PaymentResult cancel(Long paymentId) {
        return PaymentResult.successWithoutPayment();
    }
}
