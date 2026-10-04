package com.capturetotext.app.gateway;

import com.capturetotext.app.model.Payment;

/**
 * The only thing PaymentService knows about the payment provider. Stripe implements it
 * in production; tests pass in a fake, so business logic is tested without network calls.
 */
public interface PaymentGateway {

    CheckoutSession createCheckoutSession(Payment payment);
}
