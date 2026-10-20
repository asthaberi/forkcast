package com.asthaberi.forkcast.review.summary;

import java.util.List;

/**
 * What the language model returns, mapped from JSON by Spring AI's structured output. Only the wording
 * comes from the model; ratings, counts and the star distribution are computed by the database.
 */
public record ReviewSummaryDraft(String summary, List<String> pros, List<String> cons, Sentiment sentiment,
                                 List<String> mentionedDishes) {

    public enum Sentiment {
        POSITIVE,
        MIXED,
        NEGATIVE
    }
}
