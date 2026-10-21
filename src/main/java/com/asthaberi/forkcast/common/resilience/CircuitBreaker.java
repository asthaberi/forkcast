package com.asthaberi.forkcast.common.resilience;

import java.time.Duration;
import java.util.function.LongSupplier;

/**
 * Stops calling a dependency for a while after it fails, so an outage costs one timeout instead of one timeout
 * per request. Once the pause is over, the next call goes through as a trial: if it fails, the pause starts again.
 *
 * Deliberately minimal (one failure opens it, a fixed pause): the callers it protects (Redis as a cache and as a
 * rate limiter) have a cheap fallback, so skipping a working Redis for a few seconds costs little.
 */
public final class CircuitBreaker {

    private final Duration openFor;
    private final LongSupplier nanoClock;
    // nanoTime values can only be compared by subtraction, so this starts at "now" (already closed), never at a
    // sentinel like Long.MIN_VALUE, where the subtraction would overflow
    private volatile long closesAt;

    public CircuitBreaker(Duration openFor) {
        this(openFor, System::nanoTime);
    }

    public CircuitBreaker(Duration openFor, LongSupplier nanoClock) {
        this.openFor = openFor;
        this.nanoClock = nanoClock;
        this.closesAt = nanoClock.getAsLong();
    }

    /** True while calls should be skipped. */
    public boolean isOpen() {
        return nanoClock.getAsLong() - closesAt < 0;
    }

    public void recordFailure() {
        closesAt = nanoClock.getAsLong() + openFor.toNanos();
    }

    public Duration openFor() {
        return openFor;
    }
}
