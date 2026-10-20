package com.asthaberi.forkcast.review.summary;

import java.util.List;

import com.asthaberi.forkcast.review.Review;

/** Writes a short summary of a restaurant's reviews. Implemented with Gemini; replaced by a stub in tests. */
public interface ReviewSummarizer {

    boolean isAvailable();

    String modelName();

    ReviewSummaryDraft summarize(String restaurantName, List<String> menuDishes, List<Review> reviews);
}
