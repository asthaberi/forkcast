package com.asthaberi.forkcast.common.cache;

public final class CacheNames {

    /** Restaurant menu by restaurant id. Evicted whenever an item changes. */
    public static final String MENUS = "menus";

    /** Restaurants near a geohash cell, keyed by cell + radius + cuisine. Expires by TTL. */
    public static final String NEARBY_RESTAURANTS = "nearbyRestaurants";

    private CacheNames() {
    }
}
