package com.asthaberi.forkcast.common.cache;

public final class CacheNames {

    /** Restaurant menu by restaurant id. Evicted whenever an item changes. */
    public static final String MENUS = "menus";

    /** Restaurants near a geohash cell, keyed by cell + radius + cuisine. Expires by TTL. */
    public static final String NEARBY_RESTAURANTS = "nearbyRestaurants";

    /** Dishes most ordered near you, keyed by the set of nearby restaurants. Expires by TTL. */
    public static final String POPULAR_DISHES = "popularDishes";

    private CacheNames() {
    }
}
