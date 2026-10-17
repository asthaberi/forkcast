package com.asthaberi.forkcast.restaurant;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.LocalTime;

import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

class RestaurantTest {

    @ParameterizedTest(name = "{0}-{1} at {2} -> open={3}")
    @CsvSource({
            // same-day hours
            "11:00, 23:00, 10:59, false",
            "11:00, 23:00, 11:00, true",
            "11:00, 23:00, 22:59, true",
            "11:00, 23:00, 23:00, false",
            // overnight hours
            "18:00, 03:00, 17:59, false",
            "18:00, 03:00, 18:00, true",
            "18:00, 03:00, 00:30, true",
            "18:00, 03:00, 03:00, false"
    })
    void isOpenAt(LocalTime opensAt, LocalTime closesAt, LocalTime now, boolean expectedOpen) {
        Restaurant restaurant = Restaurant.builder().opensAt(opensAt).closesAt(closesAt).build();

        assertThat(restaurant.isOpenAt(now)).isEqualTo(expectedOpen);
    }
}
