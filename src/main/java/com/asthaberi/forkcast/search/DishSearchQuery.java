package com.asthaberi.forkcast.search;

import java.math.BigDecimal;

/**
 * A semantic dish search. Location is optional: with it, only restaurants delivering to that point are
 * searched; without it, the whole catalogue.
 */
public record DishSearchQuery(String text, Double latitude, Double longitude, Double radiusKm, Boolean vegetarian,
                              BigDecimal maxPrice, Integer limit) {

    boolean hasLocation() {
        return latitude != null && longitude != null;
    }
}
