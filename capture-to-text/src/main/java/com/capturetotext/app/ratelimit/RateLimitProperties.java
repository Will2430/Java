package com.capturetotext.app.ratelimit;

import org.springframework.boot.context.properties.ConfigurationProperties;

// Binds app.rate-limit.payments.* and app.rate-limit.api.* (see application.properties).
@ConfigurationProperties(prefix = "app.rate-limit")
public record RateLimitProperties(Bucket payments, Bucket api) {

    // capacity = the burst a user may send at once; refillPerMinute = the sustained rate after that.
    public record Bucket(long capacity, long refillPerMinute) {

        double refillPerMilli() {
            return refillPerMinute / 60_000.0;
        }
    }
}
