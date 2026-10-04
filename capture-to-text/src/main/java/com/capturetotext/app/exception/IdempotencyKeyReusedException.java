package com.capturetotext.app.exception;

// Same Idempotency-Key but a different request body: almost always a client bug, so refuse it.
public class IdempotencyKeyReusedException extends RuntimeException {
    public IdempotencyKeyReusedException(String key) {
        super("Idempotency-Key '" + key + "' was already used with a different request body.");
    }
}
