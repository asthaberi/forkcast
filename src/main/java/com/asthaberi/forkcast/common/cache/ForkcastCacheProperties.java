package com.asthaberi.forkcast.common.cache;

import java.time.Duration;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Time-to-live per cache, bound from {@code forkcast.cache.*}.
 *
 * @param menuTtl   menus are also evicted on every change, so the TTL is only a safety net
 * @param nearbyTtl nearby results aren't evicted on writes, so this bounds how stale they can get
 * @param popularTtl popularity over the last 30 days barely moves in minutes, and is costly to compute
 */
@ConfigurationProperties(prefix = "forkcast.cache")
public record ForkcastCacheProperties(Duration menuTtl, Duration nearbyTtl, Duration popularTtl) {
}
