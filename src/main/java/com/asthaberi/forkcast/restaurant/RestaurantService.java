package com.asthaberi.forkcast.restaurant;

import java.time.Clock;
import java.time.LocalTime;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;

import com.asthaberi.forkcast.common.exception.ResourceNotFoundException;
import com.asthaberi.forkcast.common.geo.GeoHash;
import com.asthaberi.forkcast.common.geo.GeoUtils;
import com.asthaberi.forkcast.restaurant.dto.CreateRestaurantRequest;
import com.asthaberi.forkcast.restaurant.dto.RestaurantResponse;
import com.asthaberi.forkcast.restaurant.dto.RestaurantSearchResponse;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

@Service
@RequiredArgsConstructor
public class RestaurantService {

    private final RestaurantRepository restaurantRepository;
    private final NearbyRestaurantFinder nearbyRestaurantFinder;
    private final SearchProperties searchProperties;
    private final Clock clock;

    /**
     * @param radiusKm search radius; when {@code null}, the serving radius for the current time is used
     * @param openNow  when true, only restaurants open right now are returned
     */
    public RestaurantSearchResponse searchNearby(double latitude, double longitude, Double radiusKm,
                                                 String cuisine, boolean openNow) {
        LocalTime now = LocalTime.now(clock);
        double radius = radiusKm != null ? radiusKm : searchProperties.defaultRadiusAt(now);
        String cell = GeoHash.encode(latitude, longitude, searchProperties.cellPrecision());

        // The cached result is shared by everyone in the cell; open-now and distance depend on the
        // current time and the user's exact position, so they're computed here on every request.
        List<RestaurantResponse> restaurants = nearbyRestaurantFinder
                .findInCell(cell, radius, normalizeCuisine(cuisine))
                .stream()
                .filter(restaurant -> !openNow || restaurant.isOpenAt(now))
                .map(restaurant -> RestaurantMapper.toResponse(restaurant, now,
                        GeoUtils.distanceKm(latitude, longitude, restaurant.latitude(), restaurant.longitude())))
                .sorted(Comparator.comparing(RestaurantResponse::distanceKm))
                .toList();

        return new RestaurantSearchResponse(radius, restaurants);
    }

    public RestaurantResponse getById(String id) {
        Restaurant restaurant = restaurantRepository.findById(id)
                .orElseThrow(() -> new ResourceNotFoundException("Restaurant", id));
        return RestaurantMapper.toResponse(RestaurantSnapshot.from(restaurant), LocalTime.now(clock), null);
    }

    public void requireExists(String id) {
        if (!restaurantRepository.existsById(id)) {
            throw new ResourceNotFoundException("Restaurant", id);
        }
    }

    /** @param ownerId the user creating the restaurant, who becomes its owner */
    public RestaurantResponse create(CreateRestaurantRequest request, String ownerId) {
        Restaurant saved = restaurantRepository.save(RestaurantMapper.toEntity(request, ownerId));
        return RestaurantMapper.toResponse(RestaurantSnapshot.from(saved), LocalTime.now(clock), null);
    }

    // One spelling per cuisine so "Pizza", " pizza" and "PIZZA" share a cache entry.
    private static String normalizeCuisine(String cuisine) {
        return cuisine == null || cuisine.isBlank() ? null : cuisine.trim().toLowerCase(Locale.ROOT);
    }
}
