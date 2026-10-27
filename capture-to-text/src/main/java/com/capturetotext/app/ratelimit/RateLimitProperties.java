package com.capturetotext.app.ratelimit;

import org.springframework.boot.context.properties.ConfigurationProperties;

// Binds app.rate-limit.payments.* and app.rate-limit.api.* (see application.properties).
@ConfigurationProperties(prefix = "app.rate-limit")
public record RateLimitProperties(Bucket payments, Bucket api) {

    // capacity = the burst a user may send at once; refillPerMinute = the sustained rate after that.
    public record Bucket(long capacity, long refillPerMinute) {

        /* - token/minutes x 1 minute/ 60,000ms = tokens/60,000ms 
           - this conversion works because 1/60,000 represents the same duration and therefore evaluates to 1, the trick here is to cancel out the 'minute' unit, i.e. leaving token/ms          
        */
        double refillPerMilli() {
            return refillPerMinute / 60_000.0;
        }
    }
}
