package com.asthaberi.forkcast.recommendation.dto;

import java.math.BigDecimal;
import java.util.List;

import com.fasterxml.jackson.annotation.JsonInclude;

/**
 * @param strategy       PERSONALIZED when "forYou" comes from the customer's taste, POPULAR for customers
 *                       with no history yet (or when the AI index isn't available)
 * @param vegetarianOnly true when the history is all vegetarian, so only vegetarian dishes were considered
 * @param orderAgain     the customer's favourites, available now from restaurants that deliver to them
 * @param forYou         dishes they haven't ordered yet, each with the reason it was picked
 */
public record RecommendationsResponse(Strategy strategy, double radiusKm, boolean vegetarianOnly,
                                      List<RecommendedDish> orderAgain, List<RecommendedDish> forYou) {

    public enum Strategy {
        PERSONALIZED,
        POPULAR
    }

    /**
     * @param reason e.g. "Because you ordered Masala Dosa" or "Ordered by 12 people near you this month"
     * @param score  for personal matches: cosine similarity to the customer's taste vector
     */
    @JsonInclude(JsonInclude.Include.NON_NULL)
    public record RecommendedDish(String menuItemId, String name, String category, BigDecimal price,
                                  boolean vegetarian, String restaurantId, String restaurantName, Double distanceKm,
                                  boolean openNow, String reason, Double score) {

        public RecommendedDish withReason(String newReason) {
            return new RecommendedDish(menuItemId, name, category, price, vegetarian, restaurantId, restaurantName,
                    distanceKm, openNow, newReason, score);
        }
    }
}
