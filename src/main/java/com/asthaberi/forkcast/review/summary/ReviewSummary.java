package com.asthaberi.forkcast.review.summary;

import java.time.Duration;
import java.time.Instant;
import java.util.List;

import com.asthaberi.forkcast.review.summary.ReviewSummaryDraft.Sentiment;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;
import org.springframework.data.annotation.Id;
import org.springframework.data.mongodb.core.mapping.Document;

/**
 * A generated summary, stored so it's written once and read many times: every page view calling the LLM
 * would be slow, costly and burn through the free-tier quota. One document per restaurant (id = restaurantId).
 */
@Document(collection = "review_summaries")
@Getter
@NoArgsConstructor(access = AccessLevel.PRIVATE)
public class ReviewSummary {

    /** A summary is refreshed once this many reviews arrived since it was written... */
    static final int NEW_REVIEWS_FOR_REFRESH = 3;
    /** ...or when it's this old and at least one review arrived. */
    static final Duration MAX_AGE_WITH_NEW_REVIEWS = Duration.ofHours(6);

    @Id
    private String restaurantId;

    private String summary;

    private List<String> pros;

    private List<String> cons;

    private Sentiment sentiment;

    private List<String> mentionedDishes;

    // The restaurant's review count when this was written, to tell how many reviews it hasn't seen
    private int basedOnReviews;

    private String model;

    private Instant generatedAt;

    static ReviewSummary of(String restaurantId, ReviewSummaryDraft draft, int basedOnReviews, String model,
                            Instant generatedAt) {
        ReviewSummary summary = new ReviewSummary();
        summary.restaurantId = restaurantId;
        summary.summary = draft.summary();
        summary.pros = draft.pros();
        summary.cons = draft.cons();
        summary.sentiment = draft.sentiment();
        summary.mentionedDishes = draft.mentionedDishes();
        summary.basedOnReviews = basedOnReviews;
        summary.model = model;
        summary.generatedAt = generatedAt;
        return summary;
    }

    boolean isStale(int currentReviewCount, Instant now) {
        int unseen = currentReviewCount - basedOnReviews;
        return unseen >= NEW_REVIEWS_FOR_REFRESH
                || (unseen > 0 && generatedAt.plus(MAX_AGE_WITH_NEW_REVIEWS).isBefore(now));
    }
}
