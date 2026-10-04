package com.capturetotext.app.exception;

// The caller used up their token bucket. Mapped to 429 Too Many Requests with Retry-After.
public class RateLimitExceededException extends RuntimeException {

    private final long retryAfterMillis;

    public RateLimitExceededException(long retryAfterMillis) {
        super("Too many requests. Retry in " + Math.max(1, (retryAfterMillis + 999) / 1000) + "s.");
        this.retryAfterMillis = retryAfterMillis;
    }

    // Retry-After is whole seconds; round up so a client that honours it never retries too early.
    public long retryAfterSeconds() {
        return Math.max(1, (retryAfterMillis + 999) / 1000);
    }
}
