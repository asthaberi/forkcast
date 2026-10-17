package com.asthaberi.forkcast.restaurant;

import java.util.List;

import com.asthaberi.forkcast.common.cache.CacheNames;
import com.asthaberi.forkcast.common.geo.Coordinates;
import com.asthaberi.forkcast.common.geo.GeoHash;
import lombok.RequiredArgsConstructor;
import org.springframework.cache.annotation.Cacheable;
import org.springframework.stereotype.Component;

/**
 * Cached nearby lookup, keyed by geohash cell instead of exact coordinates.
 *
 * Exact lat/lng would almost never repeat, so the cache would never hit. Instead, every user
 * inside the same ~150 m cell shares one cached result, queried from the cell's center.
 *
 * This is a separate bean from RestaurantService on purpose: @Cacheable works through a Spring
 * proxy, so a call from one method to another inside the same class would skip the cache.
 */
@Component
@RequiredArgsConstructor
public class NearbyRestaurantFinder {

    // Upper bound per search so one request can't pull the whole collection.
    static final int MAX_RESULTS = 100;

    private final RestaurantRepository restaurantRepository;

    @Cacheable(cacheNames = CacheNames.NEARBY_RESTAURANTS, key = "#cell + ':' + #radiusKm + ':' + (#cuisine ?: 'any')")
    public List<RestaurantSnapshot> findInCell(String cell, double radiusKm, String cuisine) {
        Coordinates center = GeoHash.decodeCenter(cell);
        return restaurantRepository
                .findNearby(center.latitude(), center.longitude(), radiusKm, cuisine, MAX_RESULTS)
                .stream()
                .map(RestaurantSnapshot::from)
                .toList();
    }
}
