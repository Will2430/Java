package com.capturetotext.app.model;

import java.time.Instant;

// An event waiting to be published to Kafka, stored inside its Payment document
// (see Payment#outbox). OutboxRelay removes it once Kafka acknowledges it.
public record OutboxEvent(String eventId, String type, PaymentStatus status, Instant occurredAt) {
}
