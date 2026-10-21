package com.asthaberi.forkcast.ratelimit;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Duration;
import java.util.HexFormat;
import java.util.List;
import java.util.function.LongSupplier;

import com.asthaberi.forkcast.common.resilience.CircuitBreaker;
import com.asthaberi.forkcast.ratelimit.RateLimitProperties.Policy;
import io.micrometer.core.instrument.MeterRegistry;
import lombok.extern.slf4j.Slf4j;
import org.springframework.core.io.ClassPathResource;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.RedisScript;

/**
 * Distributed token-bucket rate limiter: one Redis round trip per check, shared by all app instances.
 *
 * Fails open: if Redis is unreachable the request is allowed and the error is counted and logged.
 * Limits protect against abuse, but they shouldn't take the whole API down with them.
 *
 * Circuit breaker: after a failure, Redis is skipped for a few seconds. Otherwise, during an outage every
 * request would wait out the Redis timeout before being allowed anyway.
 */
@Slf4j
public class RedisRateLimiter implements RateLimiter {

    private static final String KEY_PREFIX = "forkcast:rate-limit:";
    static final Duration SKIP_AFTER_FAILURE = Duration.ofSeconds(5);

    @SuppressWarnings("rawtypes")
    private static final RedisScript<List> TOKEN_BUCKET =
            RedisScript.of(new ClassPathResource("scripts/token_bucket.lua"), List.class);

    private final StringRedisTemplate redisTemplate;
    private final RateLimitProperties properties;
    private final MeterRegistry meterRegistry;
    private final CircuitBreaker breaker;

    public RedisRateLimiter(StringRedisTemplate redisTemplate, RateLimitProperties properties,
                            MeterRegistry meterRegistry) {
        this(redisTemplate, properties, meterRegistry, System::nanoTime);
    }

    RedisRateLimiter(StringRedisTemplate redisTemplate, RateLimitProperties properties, MeterRegistry meterRegistry,
                     LongSupplier nanoClock) {
        this.redisTemplate = redisTemplate;
        this.properties = properties;
        this.meterRegistry = meterRegistry;
        this.breaker = new CircuitBreaker(SKIP_AFTER_FAILURE, nanoClock);
    }

    @Override
    public RateLimitDecision tryConsume(String policyName, String key) {
        Policy policy = properties.policy(policyName);
        if (breaker.isOpen()) {
            count(policyName, "skipped");
            return RateLimitDecision.unlimited();
        }
        try {
            List<?> result = redisTemplate.execute(TOKEN_BUCKET, List.of(redisKey(policyName, key)),
                    String.valueOf(policy.capacity()), String.valueOf(policy.refillTokens()),
                    String.valueOf(policy.refillPeriod().toMillis()), "1");
            boolean allowed = ((Number) result.get(0)).longValue() == 1;
            RateLimitDecision decision = new RateLimitDecision(allowed, policy.capacity(),
                    ((Number) result.get(1)).longValue(), Duration.ofMillis(((Number) result.get(2)).longValue()));
            count(policyName, allowed ? "allowed" : "limited");
            return decision;
        } catch (RuntimeException e) {
            count(policyName, "error");
            breaker.recordFailure();
            log.warn("Rate limit check '{}' failed; allowing requests without limits for {} s: {}",
                    policyName, SKIP_AFTER_FAILURE.toSeconds(), e.toString());
            return RateLimitDecision.unlimited();
        }
    }

    private void count(String policy, String outcome) {
        meterRegistry.counter("forkcast.rate_limit.decisions", "policy", policy, "outcome", outcome).increment();
    }

    /** Keys are hashed so values like email addresses aren't stored in Redis in plain text. */
    static String redisKey(String policy, String key) {
        try {
            byte[] hash = MessageDigest.getInstance("SHA-256").digest(key.getBytes(StandardCharsets.UTF_8));
            return KEY_PREFIX + policy + ":" + HexFormat.of().formatHex(hash, 0, 16);
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }
}
