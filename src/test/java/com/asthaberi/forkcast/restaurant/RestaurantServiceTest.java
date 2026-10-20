package com.asthaberi.forkcast.restaurant;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.anyDouble;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.time.Clock;
import java.time.LocalTime;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.util.List;
import java.util.Optional;

import com.asthaberi.forkcast.common.exception.ResourceNotFoundException;
import com.asthaberi.forkcast.common.geo.GeoHash;
import com.asthaberi.forkcast.restaurant.SearchProperties.PeakWindow;
import com.asthaberi.forkcast.restaurant.dto.RestaurantSearchResponse;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
class RestaurantServiceTest {

    private static final ZoneId IST = ZoneId.of("Asia/Kolkata");
    private static final double USER_LAT = 26.2124;
    private static final double USER_LNG = 78.1772;
    private static final String USER_CELL = GeoHash.encode(USER_LAT, USER_LNG, 7);

    private final SearchProperties searchProperties =
            new SearchProperties(5.0, 3.0, List.of(new PeakWindow(8, 10), new PeakWindow(19, 21)), 7);

    @Mock
    private RestaurantRepository restaurantRepository;

    @Mock
    private NearbyRestaurantFinder nearbyRestaurantFinder;

    @Test
    void usesPeakRadiusDuringPeakHours() {
        RestaurantSearchResponse response = serviceAt(9, 0).searchNearby(USER_LAT, USER_LNG, null, null, false);

        assertThat(response.radiusKm()).isEqualTo(3.0);
        verify(nearbyRestaurantFinder).findInCell(USER_CELL, 3.0, null);
    }

    @Test
    void usesNormalRadiusOutsidePeakHours() {
        RestaurantSearchResponse response = serviceAt(11, 0).searchNearby(USER_LAT, USER_LNG, null, null, false);

        assertThat(response.radiusKm()).isEqualTo(5.0);
        verify(nearbyRestaurantFinder).findInCell(USER_CELL, 5.0, null);
    }

    @Test
    void explicitRadiusOverridesDefaultAndCuisineIsNormalized() {
        RestaurantSearchResponse response = serviceAt(9, 0).searchNearby(USER_LAT, USER_LNG, 10.0, "  PIZZA ", false);

        assertThat(response.radiusKm()).isEqualTo(10.0);
        verify(nearbyRestaurantFinder).findInCell(USER_CELL, 10.0, "pizza");
    }

    @Test
    void openNowFiltersClosedRestaurantsAndSortsByUsersOwnDistance() {
        RestaurantSnapshot farOpen = snapshot("1", "Far Open", 26.2300, 78.1900, "11:00", "23:00");
        RestaurantSnapshot nearOpen = snapshot("2", "Near Open", 26.2150, 78.1800, "11:00", "23:00");
        RestaurantSnapshot closed = snapshot("3", "Dinner Only", 26.2200, 78.1700, "18:00", "03:00");
        // Cached order was computed from the cell center, so it may not match this user's order.
        when(nearbyRestaurantFinder.findInCell(anyString(), anyDouble(), isNull()))
                .thenReturn(List.of(farOpen, nearOpen, closed));

        RestaurantSearchResponse response = serviceAt(12, 0).searchNearby(USER_LAT, USER_LNG, null, null, true);

        assertThat(response.restaurants()).extracting("name").containsExactly("Near Open", "Far Open");
        assertThat(response.restaurants().getFirst().openNow()).isTrue();
        assertThat(response.restaurants().getFirst().distanceKm()).isBetween(0.3, 0.5);
    }

    @Test
    void getByIdThrowsWhenMissing() {
        when(restaurantRepository.findById("missing")).thenReturn(Optional.empty());

        assertThatThrownBy(() -> serviceAt(12, 0).getById("missing"))
                .isInstanceOf(ResourceNotFoundException.class)
                .hasMessageContaining("missing");
    }

    @Test
    void requireExistsThrowsWhenMissing() {
        when(restaurantRepository.existsById("missing")).thenReturn(false);

        assertThatThrownBy(() -> serviceAt(12, 0).requireExists("missing"))
                .isInstanceOf(ResourceNotFoundException.class);
    }

    private RestaurantService serviceAt(int hour, int minute) {
        ZonedDateTime time = ZonedDateTime.of(2026, 10, 17, hour, minute, 0, 0, IST);
        Clock clock = Clock.fixed(time.toInstant(), IST);
        return new RestaurantService(restaurantRepository, nearbyRestaurantFinder, searchProperties, clock);
    }

    private static RestaurantSnapshot snapshot(String id, String name, double lat, double lng, String opens, String closes) {
        return new RestaurantSnapshot(id, name, "address", "Gwalior", lat, lng, List.of("north indian"), 4.0, 0,
                LocalTime.parse(opens), LocalTime.parse(closes), null);
    }
}
