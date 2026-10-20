package com.asthaberi.forkcast.review.dto;

import java.time.Instant;
import java.util.List;
import java.util.Map;

import com.asthaberi.forkcast.review.summary.ReviewSummaryDraft.Sentiment;

/**
 * What customers see above the review list. The numbers (average, count, star distribution) always come
 * from the database and are current; the text fields come from the stored AI summary.
 *
 * @param starDistribution number of reviews per star rating, 5 down to 1
 * @param basedOnReviews   how many reviews existed when the summary was written
 * @param stale            true when the summary predates several newer reviews; a fresh one is then written in
 *                         the background (when Gemini is configured)
 */
public record ReviewSummaryResponse(String restaurantId, Status status, double averageRating, int reviewCount,
                                    Map<Integer, Integer> starDistribution, String summary, List<String> pros,
                                    List<String> cons, Sentiment sentiment, List<String> mentionedDishes,
                                    Integer basedOnReviews, Instant generatedAt, String model, boolean stale) {

    public enum Status {
        READY,
        NOT_ENOUGH_REVIEWS
    }
}
