package com.asthaberi.forkcast.common.resilience;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Duration;
import java.util.concurrent.atomic.AtomicLong;

import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.Test;

class CircuitBreakerTest {

    @Test
    void publishesWhetherItIsOpenAndCountsFailures() {
        AtomicLong nanos = new AtomicLong(1_000);
        SimpleMeterRegistry registry = new SimpleMeterRegistry();
        CircuitBreaker breaker = new CircuitBreaker(Duration.ofSeconds(5), nanos::get).withMetrics(registry, "redis-cache");

        assertThat(registry.get("forkcast.circuit_breaker.open").tag("name", "redis-cache").gauge().value()).isZero();

        breaker.recordFailure();
        assertThat(registry.get("forkcast.circuit_breaker.open").gauge().value()).isEqualTo(1);
        assertThat(registry.get("forkcast.circuit_breaker.failures").tag("name", "redis-cache").counter().count())
                .isEqualTo(1);

        nanos.addAndGet(Duration.ofSeconds(5).toNanos());
        assertThat(registry.get("forkcast.circuit_breaker.open").gauge().value()).isZero();
    }
}
