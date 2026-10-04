package com.capturetotext.app.exception;

// We couldn't reach the provider, or it asked us to slow down, so the outcome is unknown,
// not failed: the payment stays CREATED and a retry with the same key resumes it. Mapped to 503.
public class PaymentGatewayUnavailableException extends PaymentGatewayException {
    public PaymentGatewayUnavailableException(String message, Throwable cause) {
        super(message, cause);
    }
}
