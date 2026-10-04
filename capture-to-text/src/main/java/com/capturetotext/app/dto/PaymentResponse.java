package com.capturetotext.app.dto;

import com.capturetotext.app.model.Payment;
import com.capturetotext.app.model.PaymentStatus;

import java.time.Instant;

// What clients see. Leaves out internals like the outbox, version and request hash.
public record PaymentResponse(
        String id,
        String captureId,
        long amountCents,
        String currency,
        String description,
        PaymentStatus status,
        String checkoutUrl,
        String failureReason,
        Instant createdAt,
        Instant updatedAt
) {
    public static PaymentResponse from(Payment payment) {
        return new PaymentResponse(
                payment.getId(),
                payment.getCaptureId(),
                payment.getAmountCents(),
                payment.getCurrency(),
                payment.getDescription(),
                payment.getStatus(),
                payment.getCheckoutUrl(),
                payment.getFailureReason(),
                payment.getCreatedAt(),
                payment.getUpdatedAt()
        );
    }
}
