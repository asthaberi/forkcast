package com.asthaberi.forkcast.restaurant.dto;

import java.util.List;

/**
 * Search results plus the radius actually used, since the default radius depends on peak hours.
 */
public record RestaurantSearchResponse(double radiusKm, int count, List<RestaurantResponse> restaurants) {

    public RestaurantSearchResponse(double radiusKm, List<RestaurantResponse> restaurants) {
        this(radiusKm, restaurants.size(), restaurants);
    }
}
