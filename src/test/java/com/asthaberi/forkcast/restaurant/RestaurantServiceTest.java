package com.asthaberi.forkcast.restaurant;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.anyDouble;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.time.Clock;
import java.time.LocalTime;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.util.List;
import java.util.Optional;

import com.asthaberi.forkcast.common.exception.ResourceNotFoundException;
import com.asthaberi.forkcast.restaurant.SearchProperties.PeakWindow;
import com.asthaberi.forkcast.restaurant.dto.RestaurantSearchResponse;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.mongodb.core.geo.GeoJsonPoint;

@ExtendWith(MockitoExtension.class)
class RestaurantServiceTest {

    private static final ZoneId IST = ZoneId.of("Asia/Kolkata");
    private static final double USER_LAT = 26.2124;
    private static final double USER_LNG = 78.1772;

    private final SearchProperties searchProperties =
            new SearchProperties(5.0, 3.0, List.of(new PeakWindow(8, 10), new PeakWindow(19, 21)));

    @Mock
    private RestaurantRepository restaurantRepository;

    @Test
    void usesPeakRadiusDuringPeakHours() {
        RestaurantService service = serviceAt(9, 0);
        when(restaurantRepository.findNearby(eq(USER_LAT), eq(USER_LNG), anyDouble(), eq(null), eq(RestaurantService.MAX_RESULTS)))
                .thenReturn(List.of());

        RestaurantSearchResponse response = service.searchNearby(USER_LAT, USER_LNG, null, null, false);

        assertThat(response.radiusKm()).isEqualTo(3.0);
        verify(restaurantRepository).findNearby(USER_LAT, USER_LNG, 3.0, null, RestaurantService.MAX_RESULTS);
    }

    @Test
    void usesNormalRadiusOutsidePeakHours() {
        RestaurantService service = serviceAt(11, 0);

        RestaurantSearchResponse response = service.searchNearby(USER_LAT, USER_LNG, null, null, false);

        assertThat(response.radiusKm()).isEqualTo(5.0);
        verify(restaurantRepository).findNearby(USER_LAT, USER_LNG, 5.0, null, RestaurantService.MAX_RESULTS);
    }

    @Test
    void explicitRadiusOverridesDefault() {
        RestaurantService service = serviceAt(9, 0);

        RestaurantSearchResponse response = service.searchNearby(USER_LAT, USER_LNG, 10.0, "pizza", false);

        assertThat(response.radiusKm()).isEqualTo(10.0);
        verify(restaurantRepository).findNearby(USER_LAT, USER_LNG, 10.0, "pizza", RestaurantService.MAX_RESULTS);
    }

    @Test
    void openNowFiltersOutClosedRestaurantsAndAddsDistance() {
        RestaurantService service = serviceAt(12, 0);
        Restaurant open = restaurant("1", "Open Place", 26.2150, 78.1800, "11:00", "23:00");
        Restaurant closed = restaurant("2", "Dinner Only", 26.2200, 78.1700, "18:00", "03:00");
        when(restaurantRepository.findNearby(USER_LAT, USER_LNG, 5.0, null, RestaurantService.MAX_RESULTS))
                .thenReturn(List.of(open, closed));

        RestaurantSearchResponse response = service.searchNearby(USER_LAT, USER_LNG, null, null, true);

        assertThat(response.count()).isEqualTo(1);
        assertThat(response.restaurants().getFirst().name()).isEqualTo("Open Place");
        assertThat(response.restaurants().getFirst().openNow()).isTrue();
        assertThat(response.restaurants().getFirst().distanceKm()).isBetween(0.3, 0.5);
    }

    @Test
    void getByIdThrowsWhenMissing() {
        RestaurantService service = serviceAt(12, 0);
        when(restaurantRepository.findById("missing")).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.getById("missing"))
                .isInstanceOf(ResourceNotFoundException.class)
                .hasMessageContaining("missing");
    }

    private RestaurantService serviceAt(int hour, int minute) {
        ZonedDateTime time = ZonedDateTime.of(2026, 10, 17, hour, minute, 0, 0, IST);
        Clock clock = Clock.fixed(time.toInstant(), IST);
        return new RestaurantService(restaurantRepository, searchProperties, clock);
    }

    private static Restaurant restaurant(String id, String name, double lat, double lng, String opens, String closes) {
        return Restaurant.builder()
                .id(id)
                .name(name)
                .location(new GeoJsonPoint(lng, lat))
                .cuisines(List.of("north indian"))
                .opensAt(LocalTime.parse(opens))
                .closesAt(LocalTime.parse(closes))
                .rating(4.0)
                .build();
    }
}
