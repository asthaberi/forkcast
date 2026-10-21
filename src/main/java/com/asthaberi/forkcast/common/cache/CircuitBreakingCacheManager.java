package com.asthaberi.forkcast.common.cache;

import java.util.Collection;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.function.Supplier;

import com.asthaberi.forkcast.common.resilience.CircuitBreaker;
import org.springframework.cache.Cache;
import org.springframework.cache.CacheManager;

/**
 * Wraps every cache of the real cache manager in a CircuitBreakingCache. All caches share one breaker because
 * they share one Redis: once a menu lookup has found Redis down, restaurant search doesn't try it either.
 */
final class CircuitBreakingCacheManager implements CacheManager {

    private final Supplier<CacheManager> delegate;
    private final CircuitBreaker breaker;
    private final ConcurrentMap<String, Cache> caches = new ConcurrentHashMap<>();

    CircuitBreakingCacheManager(Supplier<CacheManager> delegate, CircuitBreaker breaker) {
        this.delegate = delegate;
        this.breaker = breaker;
    }

    @Override
    public Cache getCache(String name) {
        Cache cache = caches.get(name);
        if (cache != null) {
            return cache;
        }
        Cache target = delegate.get().getCache(name);
        return target == null ? null : caches.computeIfAbsent(name, n -> new CircuitBreakingCache(target, breaker));
    }

    @Override
    public Collection<String> getCacheNames() {
        return delegate.get().getCacheNames();
    }
}
