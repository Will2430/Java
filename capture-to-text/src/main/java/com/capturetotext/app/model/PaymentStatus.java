package com.capturetotext.app.model;

/**
 * Payment lifecycle. PENDING means the user was sent to Stripe and we're waiting
 * for a webhook to tell us the outcome; the last three states are final.
 */
public enum PaymentStatus {
    CREATED,
    PENDING,
    SUCCEEDED,
    FAILED,
    EXPIRED;

    public boolean canTransitionTo(PaymentStatus next) {
        return switch (this) {
            case CREATED -> next == PENDING || next == FAILED;
            case PENDING -> next == SUCCEEDED || next == FAILED || next == EXPIRED;
            case SUCCEEDED, FAILED, EXPIRED -> false;
        };
    }
}
