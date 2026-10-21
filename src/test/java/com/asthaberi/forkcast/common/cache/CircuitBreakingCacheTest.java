package com.asthaberi.forkcast.common.cache;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicLong;

import com.asthaberi.forkcast.common.resilience.CircuitBreaker;
import org.junit.jupiter.api.Test;
import org.springframework.cache.concurrent.ConcurrentMapCache;

class CircuitBreakingCacheTest {

    private final AtomicLong nanos = new AtomicLong(1_000);
    private final FlakyCache redis = new FlakyCache();
    private final CircuitBreakingCache cache =
            new CircuitBreakingCache(redis, new CircuitBreaker(Duration.ofSeconds(5), nanos::get));

    @Test
    void afterAFailureRedisIsSkippedUntilThePauseIsOver() {
        redis.down = true;
        assertThatThrownBy(() -> cache.get("menu")).isInstanceOf(IllegalStateException.class); // pays the timeout once
        int callsWhenItFailed = redis.calls.size();

        assertThat(cache.get("menu")).isNull(); // a miss: the method runs against MongoDB
        cache.put("menu", "value");
        assertThat(redis.calls).hasSize(callsWhenItFailed);

        redis.down = false;
        nanos.addAndGet(Duration.ofSeconds(5).toNanos());
        cache.put("menu", "fresh");
        assertThat(cache.get("menu").get()).isEqualTo("fresh");
    }

    @Test
    void anEvictionSkippedDuringAnOutageClearsTheCacheOnceRedisIsBack() {
        cache.put("menu", "old menu");
        redis.down = true;
        assertThatThrownBy(() -> cache.get("other")).isInstanceOf(IllegalStateException.class);

        cache.evict("menu"); // the menu was edited during the outage
        redis.down = false;
        nanos.addAndGet(Duration.ofSeconds(5).toNanos());

        assertThat(cache.get("menu")).isNull(); // the old menu isn't served
        assertThat(redis.calls).contains("clear");
    }

    @Test
    void anEvictionThatFailsIsAlsoMadeUpFor() {
        cache.put("menu", "old menu");
        redis.failNextEvict = true;
        assertThatThrownBy(() -> cache.evict("menu")).isInstanceOf(IllegalStateException.class);

        nanos.addAndGet(Duration.ofSeconds(5).toNanos());
        assertThat(cache.get("menu")).isNull();
    }

    @Test
    void whileOpenALoaderRunsDirectly() {
        redis.down = true;
        assertThatThrownBy(() -> cache.get("x")).isInstanceOf(IllegalStateException.class);

        assertThat(cache.get("menu", () -> "loaded from MongoDB")).isEqualTo("loaded from MongoDB");
    }

    /** An in-memory cache that can pretend Redis is unreachable, and records what it was asked to do. */
    private static final class FlakyCache extends ConcurrentMapCache {

        final List<String> calls = new ArrayList<>();
        boolean down;
        boolean failNextEvict;

        FlakyCache() {
            super("menus");
        }

        @Override
        public ValueWrapper get(Object key) {
            record("get");
            return super.get(key);
        }

        @Override
        public void put(Object key, Object value) {
            record("put");
            super.put(key, value);
        }

        @Override
        public void evict(Object key) {
            record("evict");
            if (failNextEvict) {
                failNextEvict = false;
                throw new IllegalStateException("Redis command timed out");
            }
            super.evict(key);
        }

        @Override
        public void clear() {
            record("clear");
            super.clear();
        }

        private void record(String call) {
            calls.add(call);
            if (down) {
                throw new IllegalStateException("Redis command timed out");
            }
        }
    }
}
