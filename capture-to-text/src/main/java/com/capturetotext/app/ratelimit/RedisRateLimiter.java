package com.capturetotext.app.ratelimit;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.core.io.ClassPathResource;
import org.springframework.dao.DataAccessException;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.springframework.stereotype.Component;

import java.util.List;

/**
 * Token buckets kept in Redis, so every API Pod shares one count per user. A counter in each
 * Pod's memory would let a user through N times the limit with N Pods (the HPA runs up to 5),
 * the same reason idempotency lives in Mongo and not in a Java lock.
 */
@Component
public class RedisRateLimiter {

    private static final Logger log = LoggerFactory.getLogger(RedisRateLimiter.class);

    @SuppressWarnings("rawtypes")
    private static final DefaultRedisScript<List> TOKEN_BUCKET = tokenBucketScript();

    public record Decision(boolean allowed, long retryAfterMillis) {
    }

    // Spring sends the script's SHA with EVALSHA, so Redis caches it and the text is sent only once.
    @SuppressWarnings("rawtypes")
    private static DefaultRedisScript<List> tokenBucketScript() {
        DefaultRedisScript<List> script = new DefaultRedisScript<>();
        script.setLocation(new ClassPathResource("scripts/token_bucket.lua"));
        script.setResultType(List.class);
        return script;
    }

    private final StringRedisTemplate redis;

    public RedisRateLimiter(StringRedisTemplate redis) {
        this.redis = redis;
    }

    public Decision tryConsume(String key, RateLimitProperties.Bucket bucket) {
        try {
            List<?> result = redis.execute(TOKEN_BUCKET, List.of(key),
                    String.valueOf(bucket.capacity()), String.valueOf(bucket.refillPerMilli()));
            return new Decision(((Long) result.get(0)) == 1L, (Long) result.get(1));
        } catch (DataAccessException e) {
            // Fail open: the limiter protects us from abuse; it isn't what makes requests correct.
            // Rejecting every request because Redis is down would turn a small outage into a total one.
            log.warn("Rate limiter unavailable, allowing request: {}", e.getMessage());
            return new Decision(true, 0);
        }
    }
}
