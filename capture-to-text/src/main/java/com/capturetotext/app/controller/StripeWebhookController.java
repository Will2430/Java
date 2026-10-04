package com.capturetotext.app.controller;

import com.capturetotext.app.service.PaymentWebhookService;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;

@RestController
@RequestMapping("/api/webhooks/stripe")
public class StripeWebhookController {

    private final PaymentWebhookService webhookService;

    public StripeWebhookController(PaymentWebhookService webhookService) {
        this.webhookService = webhookService;
    }

    // The body must stay a raw String: the signature is computed over the exact bytes
    // Stripe sent, so parsing and re-serializing it first would break verification.
    @PostMapping
    public Map<String, String> receive(@RequestBody String payload,
                                       @RequestHeader("Stripe-Signature") String signature) {
        return Map.of("result", webhookService.handle(payload, signature).name());
    }
}
