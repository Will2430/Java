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


    /* - assert that a final state cant transition past/to other states
       - Junit is a Spring test dependencies its pulls in, and test related annotation belongs to it
       - Parameterized Test allows a single method to be invocated for different final states, instead of writing individual @Test, which have the
        same assert block
       - EnumSource denotes the test input source (using enum values), value denotes the enum class to draw values from, and names selet only 
        specified enum constant */
    @ParameterizedTest
    @EnumSource(value = PaymentStatus.class, names = {"SUCCEEDED", "FAILED", "EXPIRED"})
    void finalStatesCannotMoveAnywhere(PaymentStatus finalState) {

        // e.g. SUCCEEDED.canTransitionto(CREATED).isFalse()
        for (PaymentStatus to : new PaymentStatus[]{CREATED, PENDING, SUCCEEDED, FAILED, EXPIRED}) {
            assertThat(finalState.canTransitionTo(to)).isFalse();
        }
    }
}
