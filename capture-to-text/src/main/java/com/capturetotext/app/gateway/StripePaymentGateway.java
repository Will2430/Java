package com.capturetotext.app.gateway;

import com.capturetotext.app.config.StripeProperties;
import com.capturetotext.app.exception.PaymentGatewayException;
import com.capturetotext.app.exception.PaymentGatewayUnavailableException;
import com.capturetotext.app.model.Payment;
import com.stripe.exception.ApiConnectionException;
import com.stripe.exception.RateLimitException;
import com.stripe.exception.StripeException;
import com.stripe.model.checkout.Session;
import com.stripe.net.RequestOptions;
import com.stripe.param.checkout.SessionCreateParams;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.time.Instant;

/**
 * Creates a Stripe-hosted Checkout page. The user types card details on Stripe's page,
 * never ours, so this server only ever sees ids and statuses (PCI scope SAQ A).
 */
@Component
public class StripePaymentGateway implements PaymentGateway {

    // Stripe's minimum Checkout lifetime; an abandoned session expires and fires checkout.session.expired.
    private static final Duration SESSION_LIFETIME = Duration.ofMinutes(30);

    private final StripeProperties stripeProperties;
    private final String publicBaseUrl;

    public StripePaymentGateway(StripeProperties stripeProperties,
                                @Value("${app.public-base-url}") String publicBaseUrl) {
        this.stripeProperties = stripeProperties;
        this.publicBaseUrl = publicBaseUrl;
    }

    @Override
    public CheckoutSession createCheckoutSession(Payment payment) {
        String secretKey = stripeProperties.secretKey();
        if (secretKey == null || secretKey.isBlank()) {
            throw new PaymentGatewayException("Stripe secret key is not configured.");
        }

        SessionCreateParams params = SessionCreateParams.builder()
                .setMode(SessionCreateParams.Mode.PAYMENT)
                // Comes back on every webhook for this session, so we can find the Payment again.
                .setClientReferenceId(payment.getId())
                .setSuccessUrl(publicBaseUrl + "/?paymentId=" + payment.getId())
                .setCancelUrl(publicBaseUrl + "/?paymentId=" + payment.getId() + "&cancelled=true")
                .setExpiresAt(Instant.now().plus(SESSION_LIFETIME).getEpochSecond())
                .addLineItem(SessionCreateParams.LineItem.builder()
                        .setQuantity(1L)
                        .setPriceData(SessionCreateParams.LineItem.PriceData.builder()
                                .setCurrency(payment.getCurrency())
                                .setUnitAmount(payment.getAmountCents())
                                .setProductData(SessionCreateParams.LineItem.PriceData.ProductData.builder()
                                        .setName(payment.getDescription())
                                        .build())
                                .build())
                        .build())
                .build();

        // Stripe remembers this key for 24h: retrying after a timeout returns the same
        // session instead of creating a second one.
        RequestOptions options = RequestOptions.builder()
                .setApiKey(secretKey)
                .setIdempotencyKey("checkout-" + payment.getId())
                // The SDK retries network failures itself, with exponential backoff and the same key.
                .setMaxNetworkRetries(2)
                .build();

        try {
            Session session = Session.create(params, options);
            return new CheckoutSession(session.getId(), session.getUrl());
        } catch (ApiConnectionException | RateLimitException e) {
            // Timeout or connection reset: Stripe may or may not have created the session. That's
            // "unknown", not "failed"; a retry with the same idempotency key finds out safely.
            // Anything else (bad request, auth, a 5xx Stripe already stored under this key) is final.
            throw new PaymentGatewayUnavailableException("Stripe could not be reached: " + e.getMessage(), e);
        } catch (StripeException e) {
            throw new PaymentGatewayException("Stripe rejected the checkout request: " + e.getMessage(), e);
        }
    }
}
