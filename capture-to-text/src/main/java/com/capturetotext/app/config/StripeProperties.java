package com.capturetotext.app.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

// Binds stripe.secret-key and stripe.webhook-secret into one typed object.
@ConfigurationProperties(prefix = "stripe")
public record StripeProperties(String secretKey, String webhookSecret) {
}
