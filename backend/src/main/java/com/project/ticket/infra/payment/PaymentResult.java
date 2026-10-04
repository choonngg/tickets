package com.project.ticket.infra.payment;

public record PaymentResult(boolean successful) {
    public static PaymentResult success() {
        return new PaymentResult(true);
    }

    public static PaymentResult failure() {
        return new PaymentResult(false);
    }
}
