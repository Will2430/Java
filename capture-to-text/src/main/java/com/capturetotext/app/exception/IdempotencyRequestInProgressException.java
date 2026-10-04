package com.capturetotext.app.exception;

// Another request with this key is still being processed; the client should retry shortly.
public class IdempotencyRequestInProgressException extends RuntimeException {
    public IdempotencyRequestInProgressException(String key) {
        super("A request with Idempotency-Key '" + key + "' is still in progress. Retry shortly.");
    }
}
