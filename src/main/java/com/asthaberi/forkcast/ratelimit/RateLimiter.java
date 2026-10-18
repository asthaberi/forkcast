package com.asthaberi.forkcast.ratelimit;

public interface RateLimiter {

    /** Used when rate limiting is turned off: allows everything. */
    RateLimiter DISABLED = (policy, key) -> RateLimitDecision.unlimited();

    /**
     * Takes one token from the bucket for this policy and key (a user id, client IP, email...).
     */
    RateLimitDecision tryConsume(String policy, String key);

    /** Like {@link #tryConsume}, but throws {@link RateLimitExceededException} when the limit is hit. */
    default RateLimitDecision consumeOrThrow(String policy, String key) {
        RateLimitDecision decision = tryConsume(policy, key);
        if (!decision.allowed()) {
            throw new RateLimitExceededException(policy, decision);
        }
        return decision;
    }
}
