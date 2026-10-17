package com.asthaberi.forkcast.restaurant;

import java.time.Clock;
import java.time.LocalTime;
import java.util.List;

import com.asthaberi.forkcast.common.exception.ResourceNotFoundException;
import com.asthaberi.forkcast.common.geo.GeoUtils;
import com.asthaberi.forkcast.restaurant.dto.CreateRestaurantRequest;
import com.asthaberi.forkcast.restaurant.dto.RestaurantResponse;
import com.asthaberi.forkcast.restaurant.dto.RestaurantSearchResponse;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

@Service
@RequiredArgsConstructor
public class RestaurantService {

    // Upper bound per search so one request can't pull the whole collection.
    static final int MAX_RESULTS = 100;

    private final RestaurantRepository restaurantRepository;
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

        List<RestaurantResponse> restaurants = restaurantRepository
                .findNearby(latitude, longitude, radius, cuisine, MAX_RESULTS)
                .stream()
                .filter(restaurant -> !openNow || restaurant.isOpenAt(now))
                .map(restaurant -> RestaurantMapper.toResponse(restaurant, now,
                        GeoUtils.distanceKm(latitude, longitude, restaurant.latitude(), restaurant.longitude())))
                .toList();

        return new RestaurantSearchResponse(radius, restaurants);
    }

    public RestaurantResponse getById(String id) {
        Restaurant restaurant = restaurantRepository.findById(id)
                .orElseThrow(() -> new ResourceNotFoundException("Restaurant", id));
        return RestaurantMapper.toResponse(restaurant, LocalTime.now(clock), null);
    }

    public void requireExists(String id) {
        if (!restaurantRepository.existsById(id)) {
            throw new ResourceNotFoundException("Restaurant", id);
        }
    }

    public RestaurantResponse create(CreateRestaurantRequest request) {
        Restaurant saved = restaurantRepository.save(RestaurantMapper.toEntity(request));
        return RestaurantMapper.toResponse(saved, LocalTime.now(clock), null);
    }
}
