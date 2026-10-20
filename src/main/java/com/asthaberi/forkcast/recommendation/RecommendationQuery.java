package com.asthaberi.forkcast.recommendation;

/**
 * @param radiusKm null for the serving radius at the current time, as in restaurant search
 * @param openNow  only recommend from restaurants open right now
 */
public record RecommendationQuery(double latitude, double longitude, Double radiusKm, boolean openNow, int limit) {
}
