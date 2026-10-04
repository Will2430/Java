package com.capturetotext.app.service;

import com.capturetotext.app.exception.IdempotencyKeyReusedException;
import com.capturetotext.app.exception.IdempotencyRequestInProgressException;
import com.capturetotext.app.exception.InvalidPaymentRequestException;
import com.capturetotext.app.exception.PaymentGatewayException;
import com.capturetotext.app.exception.PaymentGatewayUnavailableException;
import com.capturetotext.app.gateway.CheckoutSession;
import com.capturetotext.app.gateway.PaymentGateway;
import com.capturetotext.app.model.Capture;
import com.capturetotext.app.model.Payment;
import com.capturetotext.app.model.PaymentStatus;
import com.capturetotext.app.repository.CaptureRepository;
import com.capturetotext.app.repository.PaymentRepository;
import com.capturetotext.app.service.PaymentService.PaymentResult;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.dao.OptimisticLockingFailureException;
import org.springframework.test.util.ReflectionTestUtils;

import java.time.Clock;
import java.time.ZoneOffset;
import java.util.Optional;

import static com.capturetotext.app.support.PaymentTestData.CAPTURE_ID;
import static com.capturetotext.app.support.PaymentTestData.NOW;
import static com.capturetotext.app.support.PaymentTestData.OWNER;
import static com.capturetotext.app.support.PaymentTestData.aPayment;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/**
 * Unit tests with a mocked repository and gateway: no Mongo, no Stripe, no Spring context.
 * The real unique index is exercised in the Testcontainers integration test.
 */
@ExtendWith(MockitoExtension.class)
class PaymentServiceTest {

    private static final String KEY = "key-123";

    @Mock
    private PaymentRepository paymentRepository;
    @Mock
    private CaptureRepository captureRepository;
    @Mock
    private PaymentGateway paymentGateway;

    private PaymentService paymentService;

    @BeforeEach
    void setUp() {
        paymentService = new PaymentService(paymentRepository, captureRepository, paymentGateway,
                Clock.fixed(NOW, ZoneOffset.UTC));
    }

    // The request's capture belongs to the caller.
    private void ownCapture() {
        when(captureRepository.findByIdAndOwnerId(CAPTURE_ID, OWNER)).thenReturn(Optional.of(new Capture()));
    }

    @Test
    void newKeyCreatesPaymentAndOpensCheckout() {
        when(paymentRepository.findByOwnerIdAndIdempotencyKey(OWNER, KEY)).thenReturn(Optional.empty());
        ownCapture();
        when(paymentRepository.save(any())).thenAnswer(call -> {
            Payment saved = call.getArgument(0);
            ReflectionTestUtils.setField(saved, "id", "payment-1");
            return saved;
        });
        when(paymentGateway.createCheckoutSession(any())).thenReturn(new CheckoutSession("cs_1", "https://pay/1"));

        PaymentResult result = paymentService.createPayment(OWNER, KEY, aPayment().toRequest());

        assertThat(result.replayed()).isFalse();
        assertThat(result.payment().getStatus()).isEqualTo(PaymentStatus.PENDING);
        assertThat(result.payment().getCheckoutUrl()).isEqualTo("https://pay/1");
        assertThat(result.payment().getOutbox()).extracting("type").containsExactly("payment.requested");
    }

    @Test
    void sameKeySameBodyReturnsOriginalWithoutCallingStripeAgain() {
        Payment original = aPayment().withStatus(PaymentStatus.PENDING).build();
        when(paymentRepository.findByOwnerIdAndIdempotencyKey(OWNER, KEY)).thenReturn(Optional.of(original));

        PaymentResult result = paymentService.createPayment(OWNER, KEY, aPayment().toRequest());

        assertThat(result.replayed()).isTrue();
        assertThat(result.payment()).isSameAs(original);
        verifyNoInteractions(paymentGateway);
        verify(paymentRepository, never()).save(any());
    }

    @Test
    void sameKeyDifferentBodyIsRejected() {
        when(paymentRepository.findByOwnerIdAndIdempotencyKey(OWNER, KEY))
                .thenReturn(Optional.of(aPayment().withStatus(PaymentStatus.PENDING).build()));

        assertThatThrownBy(() -> paymentService.createPayment(OWNER, KEY, aPayment().withAmountCents(999).toRequest()))
                .isInstanceOf(IdempotencyKeyReusedException.class);
    }

    @Test
    void losingTheInsertRaceIsTreatedAsARetry() {
        Payment winner = aPayment().withStatus(PaymentStatus.PENDING).build();
        when(paymentRepository.findByOwnerIdAndIdempotencyKey(OWNER, KEY))
                .thenReturn(Optional.empty())       // both requests miss the lookup...
                .thenReturn(Optional.of(winner));   // ...then the loser re-reads the winner's row
        ownCapture();
        when(paymentRepository.save(any())).thenThrow(new DuplicateKeyException("E11000 duplicate key"));

        PaymentResult result = paymentService.createPayment(OWNER, KEY, aPayment().toRequest());

        assertThat(result.replayed()).isTrue();
        assertThat(result.payment()).isSameAs(winner);
        verifyNoInteractions(paymentGateway);
    }

    @Test
    void retryWhileFirstRequestIsStillRunningGetsConflict() {
        Payment inFlight = aPayment().withCreatedAt(NOW.minusSeconds(5)).build(); // still CREATED
        when(paymentRepository.findByOwnerIdAndIdempotencyKey(OWNER, KEY)).thenReturn(Optional.of(inFlight));

        assertThatThrownBy(() -> paymentService.createPayment(OWNER, KEY, aPayment().toRequest()))
                .isInstanceOf(IdempotencyRequestInProgressException.class);
        verifyNoInteractions(paymentGateway);
    }

    @Test
    void retryAfterACrashResumesTheCheckout() {
        Payment abandoned = aPayment().withCreatedAt(NOW.minus(PaymentService.RESUME_AFTER).minusSeconds(1)).build();
        when(paymentRepository.findByOwnerIdAndIdempotencyKey(OWNER, KEY)).thenReturn(Optional.of(abandoned));
        when(paymentRepository.save(any())).thenAnswer(call -> call.getArgument(0));
        when(paymentGateway.createCheckoutSession(abandoned)).thenReturn(new CheckoutSession("cs_1", "https://pay/1"));

        PaymentResult result = paymentService.createPayment(OWNER, KEY, aPayment().toRequest());

        assertThat(result.replayed()).isTrue();
        assertThat(result.payment().getStatus()).isEqualTo(PaymentStatus.PENDING);
    }

    @Test
    void gatewayFailureMarksThePaymentFailed() {
        when(paymentRepository.findByOwnerIdAndIdempotencyKey(OWNER, KEY)).thenReturn(Optional.empty());
        ownCapture();
        when(paymentRepository.save(any())).thenAnswer(call -> call.getArgument(0));
        when(paymentGateway.createCheckoutSession(any())).thenThrow(new PaymentGatewayException("Stripe is down"));

        assertThatThrownBy(() -> paymentService.createPayment(OWNER, KEY, aPayment().toRequest()))
                .isInstanceOf(PaymentGatewayException.class);

        ArgumentCaptor<Payment> saved = ArgumentCaptor.forClass(Payment.class);
        verify(paymentRepository, times(2)).save(saved.capture());
        Payment failed = saved.getValue();
        assertThat(failed.getStatus()).isEqualTo(PaymentStatus.FAILED);
        assertThat(failed.getFailureReason()).isEqualTo("Stripe is down");
        assertThat(failed.getOutbox()).extracting("type").containsExactly("payment.failed");
    }

    @Test
    void gatewayTimeoutLeavesThePaymentRetryable() {
        when(paymentRepository.findByOwnerIdAndIdempotencyKey(OWNER, KEY)).thenReturn(Optional.empty());
        ownCapture();
        when(paymentRepository.save(any())).thenAnswer(call -> call.getArgument(0));
        when(paymentGateway.createCheckoutSession(any()))
                .thenThrow(new PaymentGatewayUnavailableException("Read timed out", null));

        assertThatThrownBy(() -> paymentService.createPayment(OWNER, KEY, aPayment().toRequest()))
                .isInstanceOf(PaymentGatewayUnavailableException.class);

        ArgumentCaptor<Payment> saved = ArgumentCaptor.forClass(Payment.class);
        verify(paymentRepository, times(2)).save(saved.capture());
        Payment unknown = saved.getValue();
        assertThat(unknown.getStatus()).isEqualTo(PaymentStatus.CREATED); // not FAILED
        assertThat(unknown.getCheckoutAttemptAt()).isNull();               // nothing in flight any more
        assertThat(unknown.getOutbox()).isEmpty();
    }

    @Test
    void retryAfterATimeoutResumesWithoutWaitingOutTheWindow() {
        Payment timedOut = aPayment().withCreatedAt(NOW.minusSeconds(2)).build(); // young, but...
        timedOut.endCheckoutAttempt();                                            // ...its attempt gave up
        when(paymentRepository.findByOwnerIdAndIdempotencyKey(OWNER, KEY)).thenReturn(Optional.of(timedOut));
        when(paymentRepository.save(any())).thenAnswer(call -> call.getArgument(0));
        when(paymentGateway.createCheckoutSession(timedOut)).thenReturn(new CheckoutSession("cs_1", "https://pay/1"));

        PaymentResult result = paymentService.createPayment(OWNER, KEY, aPayment().toRequest());

        assertThat(result.replayed()).isTrue();
        assertThat(result.payment().getStatus()).isEqualTo(PaymentStatus.PENDING);
    }

    @Test
    void twoRetriesResumingAtOnceOnlyOneCallsStripe() {
        Payment abandoned = aPayment().withCreatedAt(NOW.minus(PaymentService.RESUME_AFTER).minusSeconds(1)).build();
        when(paymentRepository.findByOwnerIdAndIdempotencyKey(OWNER, KEY)).thenReturn(Optional.of(abandoned));
        // The other retry saved its claim first, so our claim fails the @Version check.
        when(paymentRepository.save(any())).thenThrow(new OptimisticLockingFailureException("version changed"));

        assertThatThrownBy(() -> paymentService.createPayment(OWNER, KEY, aPayment().toRequest()))
                .isInstanceOf(IdempotencyRequestInProgressException.class);
        verifyNoInteractions(paymentGateway);
    }

    @Test
    void someoneElsesCaptureIsRejected() {
        when(paymentRepository.findByOwnerIdAndIdempotencyKey(OWNER, KEY)).thenReturn(Optional.empty());
        when(captureRepository.findByIdAndOwnerId(CAPTURE_ID, OWNER)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> paymentService.createPayment(OWNER, KEY, aPayment().toRequest()))
                .isInstanceOf(InvalidPaymentRequestException.class);
        verify(paymentRepository, never()).save(any());
        verifyNoInteractions(paymentGateway);
    }

    @Test
    void blankIdempotencyKeyIsRejected() {
        assertThatThrownBy(() -> paymentService.createPayment(OWNER, " ", aPayment().toRequest()))
                .isInstanceOf(InvalidPaymentRequestException.class);
        verifyNoInteractions(paymentRepository, paymentGateway);
    }
}
