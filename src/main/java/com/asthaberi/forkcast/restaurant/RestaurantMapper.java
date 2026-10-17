package com.asthaberi.forkcast.restaurant;

import java.time.LocalTime;
import java.util.Locale;

import com.asthaberi.forkcast.restaurant.dto.CreateRestaurantRequest;
import com.asthaberi.forkcast.restaurant.dto.RestaurantResponse;
import org.springframework.data.mongodb.core.geo.GeoJsonPoint;

final class RestaurantMapper {

    private RestaurantMapper() {
    }

    static Restaurant toEntity(CreateRestaurantRequest request) {
        return Restaurant.builder()
                .name(request.name().trim())
                .address(request.address().trim())
                .city(request.city().trim())
                .location(new GeoJsonPoint(request.longitude(), request.latitude()))
                .cuisines(request.cuisines().stream()
                        .map(cuisine -> cuisine.trim().toLowerCase(Locale.ROOT))
                        .distinct()
                        .toList())
                .opensAt(request.opensAt())
                .closesAt(request.closesAt())
                .rating(request.rating() == null ? 0.0 : request.rating())
                .imageUrl(request.imageUrl())
                .build();
    }

    static RestaurantResponse toResponse(Restaurant restaurant, LocalTime now, Double distanceKm) {
        return new RestaurantResponse(
                restaurant.getId(),
                restaurant.getName(),
                restaurant.getAddress(),
                restaurant.getCity(),
                restaurant.latitude(),
                restaurant.longitude(),
                restaurant.getCuisines(),
                restaurant.getRating(),
                restaurant.getOpensAt(),
                restaurant.getClosesAt(),
                restaurant.isOpenAt(now),
                distanceKm == null ? null : Math.round(distanceKm * 100) / 100.0,
                restaurant.getImageUrl());
    }
}
