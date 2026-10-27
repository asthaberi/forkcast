package com.asthaberi.forkcast.recommendation;

/**
 * How many different customers near you ordered a dish recently. Public so the Redis cache can store lists of
 * it as typed JSON (see CacheConfig).
 */
public record Popularity(String menuItemId, int customers) {
}
