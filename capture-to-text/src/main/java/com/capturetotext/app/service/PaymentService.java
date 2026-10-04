package com.capturetotext.app.service;

import com.capturetotext.app.dto.CreatePaymentRequest;
import com.capturetotext.app.exception.IdempotencyKeyReusedException;
import com.capturetotext.app.exception.IdempotencyRequestInProgressException;
import com.capturetotext.app.exception.InvalidPaymentRequestException;
import com.capturetotext.app.exception.PaymentGatewayException;
import com.capturetotext.app.exception.PaymentGatewayUnavailableException;
import com.capturetotext.app.gateway.CheckoutSession;
import com.capturetotext.app.gateway.PaymentGateway;
import com.capturetotext.app.model.Payment;
import com.capturetotext.app.model.PaymentStatus;
import com.capturetotext.app.repository.CaptureRepository;
import com.capturetotext.app.repository.PaymentRepository;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.dao.OptimisticLockingFailureException;
import org.springframework.stereotype.Service;

import java.time.Clock;
import java.time.Duration;
import java.util.Optional;

@Service
public class PaymentService {

    // A checkout attempt older than this was abandoned by a crashed request, so a retry may resume it.
    static final Duration RESUME_AFTER = Duration.ofSeconds(30);

    private final PaymentRepository paymentRepository;
    private final CaptureRepository captureRepository;
    private final PaymentGateway paymentGateway;
    private final Clock clock;

    public PaymentService(PaymentRepository paymentRepository, CaptureRepository captureRepository,
                          PaymentGateway paymentGateway, Clock clock) {
        this.paymentRepository = paymentRepository;
        this.captureRepository = captureRepository;
        this.paymentGateway = paymentGateway;
        this.clock = clock;
    }

    /**
     * Creates a payment at most once per (user, Idempotency-Key). The unique index on that
     * pair is the real guard: two simultaneous requests can both miss the lookup, but only
     * one insert succeeds, and the loser is treated as a retry.
     */
    public PaymentResult createPayment(String ownerId, String idempotencyKey, CreatePaymentRequest request) {
        if (idempotencyKey == null || idempotencyKey.isBlank() || idempotencyKey.length() > 255) {
            throw new InvalidPaymentRequestException("Idempotency-Key header must be 1-255 characters.");
        }
        String fingerprint = request.fingerprint();

        Optional<Payment> existing = paymentRepository.findByOwnerIdAndIdempotencyKey(ownerId, idempotencyKey);
        if (existing.isPresent()) {
            return replay(existing.get(), idempotencyKey, fingerprint);
        }

        // A payment may only reference the caller's own capture.
        if (request.captureId() != null && captureRepository.findByIdAndOwnerId(request.captureId(), ownerId).isEmpty()) {
            throw new InvalidPaymentRequestException("Unknown capture: " + request.captureId());
        }

        Payment payment;
        try {
            payment = paymentRepository.save(Payment.create(ownerId, idempotencyKey, fingerprint, request.captureId(),
                    request.amountCents(), request.currency(), request.description(), clock.instant()));
        } catch (DuplicateKeyException e) {
            Payment winner = paymentRepository.findByOwnerIdAndIdempotencyKey(ownerId, idempotencyKey).orElseThrow();
            return replay(winner, idempotencyKey, fingerprint);
        }
        return new PaymentResult(startCheckout(payment), false);
    }

    // Someone else's payment looks exactly like a missing one (404).
    public Optional<Payment> getPayment(String id, String ownerId) {
        return paymentRepository.findByIdAndOwnerId(id, ownerId);
    }

    private PaymentResult replay(Payment payment, String idempotencyKey, String fingerprint) {
        if (!payment.getRequestHash().equals(fingerprint)) {
            throw new IdempotencyKeyReusedException(idempotencyKey);
        }
        if (payment.getStatus() == PaymentStatus.CREATED) {
            // Another request is probably mid-way through calling Stripe; don't race it.
            if (payment.hasCheckoutAttemptSince(clock.instant().minus(RESUME_AFTER))) {
                throw new IdempotencyRequestInProgressException(idempotencyKey);
            }
            // The last attempt crashed or timed out. Claim the retry first: @Version lets only
            // one of several simultaneous retries win; the rest get 409 like above.
            payment.startCheckoutAttempt(clock.instant());
            try {
                payment = paymentRepository.save(payment);
            } catch (OptimisticLockingFailureException e) {
                throw new IdempotencyRequestInProgressException(idempotencyKey);
            }
            // Safe to resume: Stripe's own idempotency key returns the same session.
            return new PaymentResult(startCheckout(payment), true);
        }
        return new PaymentResult(payment, true);
    }

    private Payment startCheckout(Payment payment) {
        CheckoutSession session;
        try {
            session = paymentGateway.createCheckoutSession(payment);
        } catch (PaymentGatewayUnavailableException e) {
            // Unknown outcome, not a failure: stay CREATED and let the next retry resume at once.
            payment.endCheckoutAttempt();
            try {
                paymentRepository.save(payment);
            } catch (OptimisticLockingFailureException ignored) {
                // Another request moved the payment on meanwhile; its state wins.
            }
            throw e;
        } catch (PaymentGatewayException e) {
            payment.markFailed(e.getMessage(), clock.instant());
            paymentRepository.save(payment);
            throw e;
        }
        payment.markCheckoutCreated(session.id(), session.url(), clock.instant());
        try {
            return paymentRepository.save(payment);
        } catch (OptimisticLockingFailureException e) {
            // Someone else finished this payment first; return their version.
            return paymentRepository.findById(payment.getId()).orElseThrow();
        }
    }

    public record PaymentResult(Payment payment, boolean replayed) {
    }
}
