package com.asthaberi.forkcast.restaurant.dto;

import java.time.LocalTime;
import java.util.List;

import com.fasterxml.jackson.annotation.JsonInclude;

/**
 * What the API returns. Kept separate from the Restaurant document so the database
 * shape can change without breaking API clients.
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record RestaurantResponse(
        String id,
        String name,
        String address,
        String city,
        double latitude,
        double longitude,
        List<String> cuisines,
        double rating,
        int reviewCount,
        LocalTime opensAt,
        LocalTime closesAt,
        boolean openNow,
        // Only present in search results, where a user location is known.
        Double distanceKm,
        String imageUrl) {
}
