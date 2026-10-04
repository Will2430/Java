package com.capturetotext.app.ratelimit;

import com.capturetotext.app.exception.RateLimitExceededException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken;
import org.springframework.stereotype.Component;
import org.springframework.web.servlet.HandlerInterceptor;

/**
 * Runs before every /api controller method, after Spring Security has verified the token, so
 * the bucket is keyed by the real user id (the token's "sub"), not by IP: many users can share
 * one IP behind a NAT, and one attacker can rotate through many.
 */
@Component
public class RateLimitInterceptor implements HandlerInterceptor {

    private final RedisRateLimiter rateLimiter;
    private final RateLimitProperties properties;

    public RateLimitInterceptor(RedisRateLimiter rateLimiter, RateLimitProperties properties) {
        this.rateLimiter = rateLimiter;
        this.properties = properties;
    }

    @Override
    public boolean preHandle(HttpServletRequest request, HttpServletResponse response, Object handler) {
        Authentication auth = SecurityContextHolder.getContext().getAuthentication();
        if (!(auth instanceof JwtAuthenticationToken)) {
            return true; // public endpoints (the Stripe webhook): nobody to charge the request to
        }
        // Creating payments gets its own, stricter bucket: it's the expensive call (it reaches
        // Stripe), and Stripe rate-limits our whole account, so one user must not use it all up.
        boolean createPayment = "POST".equals(request.getMethod()) && "/api/payments".equals(request.getRequestURI());
        String group = createPayment ? "payments" : "api";
        RateLimitProperties.Bucket bucket = createPayment ? properties.payments() : properties.api();

        RedisRateLimiter.Decision decision = rateLimiter.tryConsume("rate:" + group + ":" + auth.getName(), bucket);
        if (!decision.allowed()) {
            // Handled by GlobalExceptionHandler: 429 Too Many Requests + Retry-After.
            throw new RateLimitExceededException(decision.retryAfterMillis());
        }
        return true;
    }
}
