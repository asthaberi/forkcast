package com.asthaberi.forkcast.restaurant;

import java.util.List;

public interface RestaurantRepositoryCustom {

    /**
     * Restaurants within {@code radiusKm} of the given point, nearest first.
     *
     * @param cuisine optional cuisine filter; {@code null} or blank means any cuisine
     */
    List<Restaurant> findNearby(double latitude, double longitude, double radiusKm, String cuisine, int limit);
}
