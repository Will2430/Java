package com.capturetotext.app.controller;

import com.capturetotext.app.dto.CreatePaymentRequest;
import com.capturetotext.app.dto.PaymentResponse;
import com.capturetotext.app.exception.PaymentNotFoundException;
import com.capturetotext.app.service.PaymentService;
import com.capturetotext.app.service.PaymentService.PaymentResult;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/payments")
public class PaymentController {

    private final PaymentService paymentService;

    public PaymentController(PaymentService paymentService) {
        this.paymentService = paymentService;
    }

    // 201 for a new payment, 200 when a retry replays the original. The client then redirects to checkoutUrl.
    // The payer is the verified token's subject, never a field in the body.
    @PostMapping
    public ResponseEntity<PaymentResponse> createPayment(@RequestHeader("Idempotency-Key") String idempotencyKey,
                                                         @Valid @RequestBody CreatePaymentRequest request,
                                                         @AuthenticationPrincipal Jwt jwt) {
        PaymentResult result = paymentService.createPayment(jwt.getSubject(), idempotencyKey, request);
        return ResponseEntity.status(result.replayed() ? HttpStatus.OK : HttpStatus.CREATED)
                .header("Idempotent-Replayed", String.valueOf(result.replayed()))
                .body(PaymentResponse.from(result.payment()));
    }

    @GetMapping("/{id}")
    public PaymentResponse getPayment(@PathVariable String id, @AuthenticationPrincipal Jwt jwt) {
        return paymentService.getPayment(id, jwt.getSubject())
                .map(PaymentResponse::from)
                .orElseThrow(() -> new PaymentNotFoundException(id));
    }
}
