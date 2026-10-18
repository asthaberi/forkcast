package com.asthaberi.forkcast.ratelimit;

import java.time.Duration;

/**
 * @param remaining  requests still allowed right now
 * @param retryAfter how long until the next request would be allowed (zero when allowed)
 */
public record RateLimitDecision(boolean allowed, int limit, long remaining, Duration retryAfter) {

    static RateLimitDecision unlimited() {
        return new RateLimitDecision(true, Integer.MAX_VALUE, Integer.MAX_VALUE, Duration.ZERO);
    }

    /** Seconds for a Retry-After header, rounded up so clients never retry too early. */
    public long retryAfterSeconds() {
        return (retryAfter.toMillis() + 999) / 1000;
    }
}
