package com.asthaberi.forkcast.restaurant;

import java.time.LocalTime;
import java.util.List;

/**
 * Plain, immutable copy of a restaurant used as the cached value.
 * We cache this rather than the MongoDB document so the cache format doesn't depend on
 * persistence types (like GeoJsonPoint) and serializes cleanly to JSON in Redis.
 * Time-dependent fields (open now) and user-dependent fields (distance) are never cached;
 * they're computed per request.
 */
public record RestaurantSnapshot(
        String id,
        String name,
        String address,
        String city,
        double latitude,
        double longitude,
        List<String> cuisines,
        double rating,
        LocalTime opensAt,
        LocalTime closesAt,
        String imageUrl) {

    static RestaurantSnapshot from(Restaurant restaurant) {
        return new RestaurantSnapshot(restaurant.getId(), restaurant.getName(), restaurant.getAddress(),
                restaurant.getCity(), restaurant.latitude(), restaurant.longitude(), restaurant.getCuisines(),
                restaurant.getRating(), restaurant.getOpensAt(), restaurant.getClosesAt(), restaurant.getImageUrl());
    }

    boolean isOpenAt(LocalTime time) {
        return OpeningHours.isOpen(opensAt, closesAt, time);
    }
}
