package com.capturetotext.app.support;

import com.capturetotext.app.dto.CreatePaymentRequest;
import com.capturetotext.app.model.Payment;
import com.capturetotext.app.model.PaymentStatus;
import org.springframework.test.util.ReflectionTestUtils;

import java.time.Instant;

/**
 * Test data builder: sensible defaults, override only what a test cares about,
 * e.g. aPayment().withAmountCents(0).build(). Reaches each status through the real
 * transitions, so a test can never build a payment the state machine couldn't produce.
 */
public final class PaymentTestData {

    public static final Instant NOW = Instant.parse("2026-10-03T10:00:00Z");
    public static final String OWNER = "user-1";
    public static final String CAPTURE_ID = "capture-1";

    private String id = "payment-1";
    private String idempotencyKey = "key-123";
    private String captureId = CAPTURE_ID;
    private long amountCents = 12_345;
    private String currency = "usd";
    private String description = "Electricity bill";
    private PaymentStatus status = PaymentStatus.CREATED;
    private Instant createdAt = NOW;

    private PaymentTestData() {
    }

    public static PaymentTestData aPayment() {
        return new PaymentTestData();
    }

    public PaymentTestData withId(String id) { this.id = id; return this; }
    public PaymentTestData withIdempotencyKey(String key) { this.idempotencyKey = key; return this; }
    public PaymentTestData withAmountCents(long amountCents) { this.amountCents = amountCents; return this; }
    public PaymentTestData withStatus(PaymentStatus status) { this.status = status; return this; }
    public PaymentTestData withCreatedAt(Instant createdAt) { this.createdAt = createdAt; return this; }

    public CreatePaymentRequest toRequest() {
        return new CreatePaymentRequest(captureId, amountCents, currency, description);
    }

    public Payment build() {
        Payment payment = Payment.create(OWNER, idempotencyKey, toRequest().fingerprint(), captureId,
                amountCents, currency, description, createdAt);
        ReflectionTestUtils.setField(payment, "id", id);
        switch (status) {
            case CREATED -> { }
            case PENDING -> payment.markCheckoutCreated("cs_test_1", "https://checkout.stripe.test/1", createdAt);
            case FAILED -> payment.markFailed("card declined", createdAt);
            case SUCCEEDED, EXPIRED -> {
                payment.markCheckoutCreated("cs_test_1", "https://checkout.stripe.test/1", createdAt);
                payment.transitionTo(status, createdAt);
            }
        }
        payment.getOutbox().clear(); // treat earlier events as already published
        return payment;
    }
}
