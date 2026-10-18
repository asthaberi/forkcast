package com.asthaberi.forkcast.ratelimit;

public class RateLimitExceededException extends RuntimeException {

    private final transient RateLimitDecision decision;

    public RateLimitExceededException(String policy, RateLimitDecision decision) {
        super("Too many requests (" + policy + "). Try again in " + decision.retryAfterSeconds() + " s.");
        this.decision = decision;
    }

    public RateLimitDecision decision() {
        return decision;
    }
}
