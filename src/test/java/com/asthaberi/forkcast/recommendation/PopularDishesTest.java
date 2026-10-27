package com.asthaberi.forkcast.recommendation;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import java.util.Set;

import org.junit.jupiter.api.Test;

class PopularDishesTest {

    @Test
    void theCacheKeyOfAnAreaIgnoresOrderAndStaysShort() {
        String key = PopularDishes.areaKey(List.of("r2", "r1", "r3"));

        assertThat(key).isEqualTo(PopularDishes.areaKey(Set.of("r3", "r2", "r1"))).hasSize(32);
        assertThat(key).isNotEqualTo(PopularDishes.areaKey(List.of("r1", "r2")));
        // Ids are separated, so ["r1", "r23"] and ["r12", "r3"] can't collide
        assertThat(PopularDishes.areaKey(List.of("r1", "r23"))).isNotEqualTo(PopularDishes.areaKey(List.of("r12", "r3")));
    }
}
