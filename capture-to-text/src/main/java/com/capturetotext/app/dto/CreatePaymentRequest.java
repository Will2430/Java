package com.capturetotext.app.dto;

import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;

/**
 * Body of POST /api/payments. Money is a whole number of the currency's smallest unit
 * (cents), the same way Stripe takes it, so there's no floating-point rounding anywhere.
 */
public record CreatePaymentRequest(
        String captureId,
        // Stripe's minimum charge is about 50 cents.
        @NotNull @Min(50) Long amountCents,
        @NotBlank @Pattern(regexp = "[a-zA-Z]{3}", message = "must be a 3-letter ISO currency code") String currency,
        @NotBlank @Size(max = 200) String description
) {

    // A hash of the request body, stored with the payment. A retry with the same
    // Idempotency-Key must have the same fingerprint, or it's rejected.
    public String fingerprint() {
        String canonical = captureId + "|" + amountCents + "|" + currency.toLowerCase() + "|" + description;
        try {
            byte[] hash = MessageDigest.getInstance("SHA-256").digest(canonical.getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(hash);
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 is always available in the JDK", e);
        }
    }
}
