package com.capturetotext.app.dto;

import com.capturetotext.app.model.PaymentStatus;

import java.time.Instant;

// JSON published to the payment-events topic. Consumers dedupe on eventId,
// because the outbox relay guarantees at-least-once delivery, not exactly-once.
public record PaymentEvent(
        String eventId,
        String type,
        String paymentId,
        // Whose payment it is, so a consumer (e.g. a receipt emailer) knows who to notify.
        String ownerId,
        String captureId,
        long amountCents,
        String currency,
        PaymentStatus status,
        Instant occurredAt
) {
}
