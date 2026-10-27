package com.asthaberi.forkcast.common.resilience;

import java.time.Duration;
import java.util.function.LongSupplier;

import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;

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
    private Counter failures;
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

    /**
     * Publishes forkcast.circuit_breaker.open (1 while open) and forkcast.circuit_breaker.failures (each failure
     * that opened it). The counter matters more: an opening lasts seconds and can fall between two scrapes, a
     * counter increase can't.
     */
    public CircuitBreaker withMetrics(MeterRegistry registry, String name) {
        Gauge.builder("forkcast.circuit_breaker.open", this, breaker -> breaker.isOpen() ? 1 : 0)
                .tag("name", name)
                .description("1 while calls to the dependency are being skipped")
                .register(registry);
        failures = Counter.builder("forkcast.circuit_breaker.failures")
                .tag("name", name)
                .description("Failures that opened the breaker")
                .register(registry);
        return this;
    }

    /** True while calls should be skipped. */
    public boolean isOpen() {
        return nanoClock.getAsLong() - closesAt < 0;
    }

    public void recordFailure() {
        closesAt = nanoClock.getAsLong() + openFor.toNanos();
        if (failures != null) {
            failures.increment();
        }
    }

    public Duration openFor() {
        return openFor;
    }
}
