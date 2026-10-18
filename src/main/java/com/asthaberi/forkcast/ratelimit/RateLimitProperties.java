package com.asthaberi.forkcast.ratelimit;

import java.time.Duration;
import java.util.Map;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Bound from {@code forkcast.rate-limit.*}.
 *
 * @param enabled  false turns every limit off (e.g. for load tests from a single machine)
 * @param policies named limits, referenced by {@link RateLimit#policy()} and the API-wide filter
 */
@ConfigurationProperties(prefix = "forkcast.rate-limit")
public record RateLimitProperties(boolean enabled, Map<String, Policy> policies) {

    public RateLimitProperties {
        policies = policies == null ? Map.of() : Map.copyOf(policies);
    }

    public Policy policy(String name) {
        Policy policy = policies.get(name);
        if (policy == null) {
            throw new IllegalArgumentException("No rate limit policy named '" + name + "'");
        }
        return policy;
    }

    /**
     * A token bucket: allows bursts of up to {@code capacity} requests, then {@code refillTokens} per
     * {@code refillPeriod} on average.
     */
    public record Policy(int capacity, int refillTokens, Duration refillPeriod) {
    }
}
