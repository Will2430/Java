package com.capturetotext.app.service;

import com.capturetotext.app.config.StripeProperties;
import com.capturetotext.app.exception.InvalidWebhookSignatureException;
import com.capturetotext.app.model.Payment;
import com.capturetotext.app.model.PaymentStatus;
import com.capturetotext.app.repository.PaymentRepository;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.stripe.exception.SignatureVerificationException;
import com.stripe.model.Event;
import com.stripe.net.Webhook;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.time.Clock;
import java.util.Optional;

/**
 * Applies Stripe's payment outcomes to our Payment records. Stripe retries a webhook until
 * it gets a 2xx and may send the same event twice, so every event id is applied at most once.
 */
@Service
public class PaymentWebhookService {

    private static final Logger log = LoggerFactory.getLogger(PaymentWebhookService.class);

    public enum Outcome { APPLIED, DUPLICATE, IGNORED }

    private final PaymentRepository paymentRepository;
    private final StripeProperties stripeProperties;
    private final ObjectMapper objectMapper;
    private final Clock clock;

    public PaymentWebhookService(PaymentRepository paymentRepository, StripeProperties stripeProperties,
                                 ObjectMapper objectMapper, Clock clock) {
        this.paymentRepository = paymentRepository;
        this.stripeProperties = stripeProperties;
        this.objectMapper = objectMapper;
        this.clock = clock;
    }

    public Outcome handle(String payload, String signatureHeader) {
        Event event = verify(payload, signatureHeader);
        JsonNode session = readSession(payload);

        Optional<PaymentStatus> target = targetStatus(event.getType(), session);
        if (target.isEmpty()) {
            return Outcome.IGNORED;
        }
        // client_reference_id is the Payment id we gave Stripe when creating the session.
        String paymentId = session.path("client_reference_id").asText(null);
        Optional<Payment> maybePayment = paymentId == null ? Optional.empty() : paymentRepository.findById(paymentId);
        if (maybePayment.isEmpty()) {
            log.warn("Webhook {} ({}) refers to unknown payment {}", event.getId(), event.getType(), paymentId);
            return Outcome.IGNORED;
        }

        Payment payment = maybePayment.get();
        if (payment.hasProcessedWebhookEvent(event.getId())) {
            return Outcome.DUPLICATE;
        }
        payment.recordWebhookEvent(event.getId());
        if (payment.getStatus().canTransitionTo(target.get())) {
            if (target.get() == PaymentStatus.FAILED) {
                payment.markFailed("Payment failed at Stripe", clock.instant());
            } else {
                payment.transitionTo(target.get(), clock.instant());
            }
        } else {
            // e.g. "expired" arriving after "completed". Acknowledge it, or Stripe retries it for days.
            log.warn("Ignoring webhook {}: payment {} is {} and cannot become {}",
                    event.getId(), paymentId, payment.getStatus(), target.get());
        }
        // One atomic write: status, outbox event and processed event id together.
        // If another request changed this payment meanwhile, @Version fails the save -> 500 -> Stripe retries.
        paymentRepository.save(payment);
        return Outcome.APPLIED;
    }

    private Event verify(String payload, String signatureHeader) {
        String secret = stripeProperties.webhookSecret();
        if (secret == null || secret.isBlank()) {
            throw new IllegalStateException("Stripe webhook secret is not configured.");
        }
        try {
            // Checks the HMAC signature and rejects events older than 5 minutes (replay protection).
            return Webhook.constructEvent(payload, signatureHeader, secret);
        } catch (SignatureVerificationException e) {
            throw new InvalidWebhookSignatureException("Invalid Stripe signature: " + e.getMessage());
        } catch (RuntimeException e) {
            throw new InvalidWebhookSignatureException("Malformed webhook payload.");
        }
    }

    // Read fields from the raw JSON: the SDK's typed object can come back empty when the
    // account's API version differs from the SDK's, and we only need two fields.
    private JsonNode readSession(String payload) {
        try {
            return objectMapper.readTree(payload).path("data").path("object");
        } catch (JsonProcessingException e) {
            throw new InvalidWebhookSignatureException("Malformed webhook payload.");
        }
    }

    private static Optional<PaymentStatus> targetStatus(String eventType, JsonNode session) {
        return switch (eventType) {
            // Cards are "paid" immediately; bank debits complete later via async_payment_succeeded.
            case "checkout.session.completed" -> "paid".equals(session.path("payment_status").asText())
                    ? Optional.of(PaymentStatus.SUCCEEDED) : Optional.empty();
            case "checkout.session.async_payment_succeeded" -> Optional.of(PaymentStatus.SUCCEEDED);
            case "checkout.session.async_payment_failed" -> Optional.of(PaymentStatus.FAILED);
            case "checkout.session.expired" -> Optional.of(PaymentStatus.EXPIRED);
            default -> Optional.empty();
        };
    }
}
