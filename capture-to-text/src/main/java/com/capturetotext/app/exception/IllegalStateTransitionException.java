package com.capturetotext.app.exception;

import com.capturetotext.app.model.PaymentStatus;

// Thrown when code (or an out-of-order webhook) attempts a move the state machine forbids.
public class IllegalStateTransitionException extends RuntimeException {
    public IllegalStateTransitionException(String paymentId, PaymentStatus from, PaymentStatus to) {
        super("Payment " + paymentId + " cannot move from " + from + " to " + to);
    }
}
