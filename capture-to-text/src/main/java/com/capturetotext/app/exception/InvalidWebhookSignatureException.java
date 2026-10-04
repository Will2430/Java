package com.capturetotext.app.exception;

// The webhook wasn't signed by Stripe (or is too old to trust), so its contents are ignored.
public class InvalidWebhookSignatureException extends RuntimeException {
    public InvalidWebhookSignatureException(String message) {
        super(message);
    }
}
