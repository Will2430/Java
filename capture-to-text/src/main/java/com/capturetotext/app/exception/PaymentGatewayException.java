package com.capturetotext.app.exception;

// The payment provider (Stripe) failed or rejected the call. Mapped to 502 Bad Gateway.
public class PaymentGatewayException extends RuntimeException {
    public PaymentGatewayException(String message) {
        super(message);
    }

    public PaymentGatewayException(String message, Throwable cause) {
        super(message, cause);
    }
}
