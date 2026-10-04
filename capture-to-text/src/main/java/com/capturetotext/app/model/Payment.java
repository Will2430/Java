package com.capturetotext.app.model;

import com.capturetotext.app.exception.IllegalStateTransitionException;
import org.springframework.data.annotation.Id;
import org.springframework.data.annotation.Version;
import org.springframework.data.mongodb.core.index.CompoundIndex;
import org.springframework.data.mongodb.core.mapping.Document;

import java.time.Instant;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;

/**
 * A bill payment made through Stripe Checkout. Status only changes through
 * transitionTo, which also queues the matching Kafka event in this same document.
 * A single-document write is atomic in Mongo, so the state and its event are saved together.
 */

// The document annotation maps the fields here to the database's fields
@Document(collection = "payments")
// Unique per (user, key), not per key: two users may pick the same key, and one user's key
// must never find another user's payment.
@CompoundIndex(name = "owner_idempotency_key", def = "{'ownerId': 1, 'idempotencyKey': 1}", unique = true)
public class Payment {

    @Id
    private String id;

    // Optimistic locking: save() fails if someone else saved this document since we read it.
    @Version
    private Long version;

    // Keycloak user id ("sub" claim) of the payer, taken from the verified token.
    private String ownerId;
    private String idempotencyKey;
    private String requestHash;

    private String captureId;
    private long amountCents;
    private String currency;
    private String description;
    
    private PaymentStatus status;
    private String checkoutSessionId;
    private String checkoutUrl;
    private String failureReason;
    private Instant createdAt;
    private Instant updatedAt;
    // When a request last started calling Stripe for this payment; null once that call has
    // given up. While an attempt is recent, a retry gets 409 instead of racing it.
    private Instant checkoutAttemptAt;

    // create doesnt include outbox because its populated by something else, hence no default values
    private List<OutboxEvent> outbox = new ArrayList<>();
    // Stripe webhook event ids already applied; Stripe can deliver the same event more than once.
    private Set<String> processedWebhookEventIds = new HashSet<>();

    protected Payment() {
    }

    public static Payment create(String ownerId, String idempotencyKey, String requestHash, String captureId,
                                 long amountCents, String currency, String description, Instant now) {
        Payment payment = new Payment();
        payment.ownerId = ownerId;
        payment.idempotencyKey = idempotencyKey;
        payment.requestHash = requestHash;
        payment.captureId = captureId;
        payment.amountCents = amountCents;
        payment.currency = currency.toLowerCase();
        payment.description = description;

        // this is the state that outbox is keep track of
        payment.status = PaymentStatus.CREATED;
        payment.createdAt = now;
        payment.updatedAt = now;
        payment.checkoutAttemptAt = now;
        return payment;
    }

    public boolean hasCheckoutAttemptSince(Instant cutoff) {
        return checkoutAttemptAt != null && checkoutAttemptAt.isAfter(cutoff);
    }

    public void startCheckoutAttempt(Instant now) {
        this.checkoutAttemptAt = now;
    }

    public void endCheckoutAttempt() {
        this.checkoutAttemptAt = null;
    }

    public void markCheckoutCreated(String sessionId, String url, Instant now) {
        this.checkoutSessionId = sessionId;
        this.checkoutUrl = url;

        // so this takes the stripe session & Url, and sets its status to the next
        transitionTo(PaymentStatus.PENDING, now);
    }

    public void markFailed(String reason, Instant now) {
        this.failureReason = reason;
        transitionTo(PaymentStatus.FAILED, now);
    }

    public void transitionTo(PaymentStatus next, Instant now) {
        if (!status.canTransitionTo(next)) {
            throw new IllegalStateTransitionException(id, status, next);
        }
        this.status = next;
        this.updatedAt = now;
        outbox.add(new OutboxEvent(UUID.randomUUID().toString(), eventTypeFor(next), next, now));
    }

    public boolean hasProcessedWebhookEvent(String eventId) {
        return processedWebhookEventIds.contains(eventId);
    }

    public void recordWebhookEvent(String eventId) {
        processedWebhookEventIds.add(eventId);
    }

    private static String eventTypeFor(PaymentStatus status) {
        return switch (status) {
            case PENDING -> "payment.requested";
            case SUCCEEDED -> "payment.completed";
            case FAILED -> "payment.failed";
            case EXPIRED -> "payment.expired";
            case CREATED -> throw new IllegalArgumentException("CREATED is never a transition target");
        };
    }

    public String getId() { return id; }
    public Long getVersion() { return version; }
    public String getOwnerId() { return ownerId; }
    public String getIdempotencyKey() { return idempotencyKey; }
    public String getRequestHash() { return requestHash; }
    public String getCaptureId() { return captureId; }
    public long getAmountCents() { return amountCents; }
    public String getCurrency() { return currency; }
    public String getDescription() { return description; }
    public PaymentStatus getStatus() { return status; }
    public String getCheckoutSessionId() { return checkoutSessionId; }
    public String getCheckoutUrl() { return checkoutUrl; }
    public String getFailureReason() { return failureReason; }
    public Instant getCreatedAt() { return createdAt; }
    public Instant getUpdatedAt() { return updatedAt; }
    public Instant getCheckoutAttemptAt() { return checkoutAttemptAt; }
    public List<OutboxEvent> getOutbox() { return outbox; }
}
