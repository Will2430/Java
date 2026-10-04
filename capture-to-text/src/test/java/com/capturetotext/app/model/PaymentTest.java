package com.capturetotext.app.model;

import com.capturetotext.app.exception.IllegalStateTransitionException;
import org.junit.jupiter.api.Test;

import static com.capturetotext.app.support.PaymentTestData.NOW;
import static com.capturetotext.app.support.PaymentTestData.aPayment;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class PaymentTest {

    @Test
    void transitionQueuesTheMatchingOutboxEvent() {
        Payment payment = aPayment().withStatus(PaymentStatus.PENDING).build();

        payment.transitionTo(PaymentStatus.SUCCEEDED, NOW);

        assertThat(payment.getStatus()).isEqualTo(PaymentStatus.SUCCEEDED);
        assertThat(payment.getOutbox()).singleElement()
                .satisfies(event -> {
                    assertThat(event.type()).isEqualTo("payment.completed");
                    assertThat(event.status()).isEqualTo(PaymentStatus.SUCCEEDED);
                });
    }

    @Test
    void invalidTransitionIsRejectedAndChangesNothing() {
        Payment payment = aPayment().withStatus(PaymentStatus.SUCCEEDED).build();

        assertThatThrownBy(() -> payment.transitionTo(PaymentStatus.FAILED, NOW))
                .isInstanceOf(IllegalStateTransitionException.class);

        assertThat(payment.getStatus()).isEqualTo(PaymentStatus.SUCCEEDED);
        assertThat(payment.getOutbox()).isEmpty();
    }

    @Test
    void currencyIsNormalisedToLowercase() {
        Payment payment = Payment.create("user-1", "k", "h", null, 500, "USD", "Bill", NOW);

        assertThat(payment.getCurrency()).isEqualTo("usd");
    }

    @Test
    void remembersProcessedWebhookEvents() {
        Payment payment = aPayment().build();

        payment.recordWebhookEvent("evt_1");

        assertThat(payment.hasProcessedWebhookEvent("evt_1")).isTrue();
        assertThat(payment.hasProcessedWebhookEvent("evt_2")).isFalse();
    }
}
