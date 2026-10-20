package com.asthaberi.forkcast.search.dto;

import java.math.BigDecimal;
import java.util.List;

import com.fasterxml.jackson.annotation.JsonInclude;

/**
 * @param radiusKm the search radius used, when a location was given
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record DishSearchResponse(String query, Double radiusKm, int count, List<DishResult> results) {

    /**
     * @param score      match strength, (1 + cosine similarity) / 2, so 0..1 with higher meaning closer
     * @param distanceKm from the searcher, when a location was given
     */
    @JsonInclude(JsonInclude.Include.NON_NULL)
    public record DishResult(String menuItemId, String name, String description, String category, BigDecimal price,
                             boolean vegetarian, String spiceLevel, String restaurantId, String restaurantName,
                             Double distanceKm, double score) {
    }
}
