package com.capturetotext.app.model;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;

import java.util.Set;

import static com.capturetotext.app.model.PaymentStatus.CREATED;
import static com.capturetotext.app.model.PaymentStatus.EXPIRED;
import static com.capturetotext.app.model.PaymentStatus.FAILED;
import static com.capturetotext.app.model.PaymentStatus.PENDING;
import static com.capturetotext.app.model.PaymentStatus.SUCCEEDED;
import static org.assertj.core.api.Assertions.assertThat;

class PaymentStatusTest {

    private static final Set<String> ALLOWED = Set.of(
            "CREATED->PENDING", "CREATED->FAILED",
            "PENDING->SUCCEEDED", "PENDING->FAILED", "PENDING->EXPIRED");

    // Checks all 25 pairs, so a newly allowed move can't sneak in unnoticed.
    @Test
    void onlyTheDocumentedTransitionsAreAllowed() {
        for (PaymentStatus from : PaymentStatus.values()) {
            for (PaymentStatus to : PaymentStatus.values()) {
                assertThat(from.canTransitionTo(to))
                        .as(from + "->" + to)
                        .isEqualTo(ALLOWED.contains(from + "->" + to));
            }
        }
    }

    @ParameterizedTest
    @EnumSource(value = PaymentStatus.class, names = {"SUCCEEDED", "FAILED", "EXPIRED"})
    void finalStatesCannotMoveAnywhere(PaymentStatus finalState) {
        for (PaymentStatus to : new PaymentStatus[]{CREATED, PENDING, SUCCEEDED, FAILED, EXPIRED}) {
            assertThat(finalState.canTransitionTo(to)).isFalse();
        }
    }
}
