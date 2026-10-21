package com.asthaberi.forkcast.common.cache;

import java.util.concurrent.Callable;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Supplier;

import com.asthaberi.forkcast.common.resilience.CircuitBreaker;
import org.springframework.cache.Cache;

/**
 * A cache that stops talking to Redis for a few seconds after a Redis error.
 *
 * Without it, a Redis outage made every cached request wait out the Redis timeout (measured: ~530 ms instead
 * of ~15 ms) before falling back to MongoDB. With it, the first failing request pays that once; for the next
 * few seconds reads are misses (the method runs against MongoDB) and writes are skipped.
 *
 * Evictions matter for correctness: a menu edited during the outage couldn't be evicted, so the old menu could
 * be served from Redis after it comes back. Any eviction that was skipped or failed is therefore remembered,
 * and this cache is cleared before it's used again.
 */
final class CircuitBreakingCache implements Cache {

    private final Cache delegate;
    private final CircuitBreaker breaker;
    private final AtomicBoolean missedEvictions = new AtomicBoolean();

    CircuitBreakingCache(Cache delegate, CircuitBreaker breaker) {
        this.delegate = delegate;
        this.breaker = breaker;
    }

    @Override
    public String getName() {
        return delegate.getName();
    }

    @Override
    public Object getNativeCache() {
        return delegate.getNativeCache();
    }

    @Override
    public ValueWrapper get(Object key) {
        return call(() -> delegate.get(key), null);
    }

    @Override
    public <T> T get(Object key, Class<T> type) {
        return call(() -> delegate.get(key, type), null);
    }

    @Override
    public <T> T get(Object key, Callable<T> valueLoader) {
        if (breaker.isOpen()) {
            try {
                return valueLoader.call();
            } catch (Exception e) {
                throw new ValueRetrievalException(key, valueLoader, e);
            }
        }
        return call(() -> delegate.get(key, valueLoader), null);
    }

    @Override
    public void put(Object key, Object value) {
        call(() -> {
            delegate.put(key, value);
            return null;
        }, null);
    }

    @Override
    public ValueWrapper putIfAbsent(Object key, Object value) {
        return call(() -> delegate.putIfAbsent(key, value), null);
    }

    @Override
    public void evict(Object key) {
        evicting(() -> {
            delegate.evict(key);
            return null;
        }, null);
    }

    @Override
    public boolean evictIfPresent(Object key) {
        return evicting(() -> delegate.evictIfPresent(key), false);
    }

    @Override
    public void clear() {
        evicting(() -> {
            delegate.clear();
            return null;
        }, null);
    }

    @Override
    public boolean invalidate() {
        return evicting(() -> delegate.invalidate(), false);
    }

    private <T> T evicting(Supplier<T> eviction, T whenOpen) {
        if (breaker.isOpen()) {
            missedEvictions.set(true);
            return whenOpen;
        }
        try {
            return call(eviction, whenOpen);
        } catch (RuntimeException e) {
            missedEvictions.set(true);
            throw e;
        }
    }

    /**
     * Runs a Redis operation unless the breaker is open. A failure opens the breaker and is rethrown to the
     * CacheErrorHandler, which logs it and lets the method run without the cache.
     */
    private <T> T call(Supplier<T> operation, T whenOpen) {
        if (breaker.isOpen()) {
            return whenOpen;
        }
        try {
            if (missedEvictions.compareAndSet(true, false)) {
                clearAfterOutage();
            }
            return operation.get();
        } catch (RuntimeException e) {
            breaker.recordFailure();
            throw e;
        }
    }

    private void clearAfterOutage() {
        try {
            delegate.clear();
        } catch (RuntimeException e) {
            missedEvictions.set(true);
            throw e;
        }
    }
}
