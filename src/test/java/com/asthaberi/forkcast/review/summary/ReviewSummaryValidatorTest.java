package com.asthaberi.forkcast.review.summary;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Duration;
import java.time.Instant;
import java.util.List;

import com.asthaberi.forkcast.review.summary.ReviewSummaryDraft.Sentiment;
import org.junit.jupiter.api.Test;

class ReviewSummaryValidatorTest {

    private static final List<String> MENU = List.of("Masala Dosa", "Filter Coffee", "Medu Vada");

    @Test
    void keepsOnlyDishesThatAreOnTheMenuInTheMenusSpelling() {
        ReviewSummaryDraft draft = new ReviewSummaryDraft("ok", List.of(), List.of(), Sentiment.POSITIVE,
                List.of("masala dosa", "Dim Sum", " Filter Coffee ", "Masala Dosa"));

        assertThat(ReviewSummaryValidator.clean(draft, MENU).mentionedDishes())
                .containsExactly("Masala Dosa", "Filter Coffee");
    }

    @Test
    void capsListsAndLengthsAndFillsMissingFields() {
        ReviewSummaryDraft draft = new ReviewSummaryDraft("x".repeat(1_000),
                List.of("a", " ", "b", "c", "d", "e", "y".repeat(300)), null, null, null);

        ReviewSummaryDraft cleaned = ReviewSummaryValidator.clean(draft, MENU);

        assertThat(cleaned.summary()).hasSize(ReviewSummaryValidator.MAX_SUMMARY_CHARS).endsWith("…");
        assertThat(cleaned.pros()).containsExactly("a", "b", "c", "d");
        assertThat(cleaned.cons()).isEmpty();
        assertThat(cleaned.sentiment()).isEqualTo(Sentiment.MIXED);
        assertThat(cleaned.mentionedDishes()).isEmpty();
    }

    @Test
    void summaryIsStaleAfterThreeNewReviewsOrWhenOldWithAnyNewReview() {
        Instant written = Instant.parse("2026-10-20T08:00:00Z");
        ReviewSummary summary = ReviewSummary.of("r1",
                new ReviewSummaryDraft("ok", List.of(), List.of(), Sentiment.POSITIVE, List.of()), 10, "m", written);

        assertThat(summary.isStale(10, written.plus(Duration.ofDays(30)))).isFalse(); // nothing new, however old
        assertThat(summary.isStale(12, written.plus(Duration.ofHours(1)))).isFalse();
        assertThat(summary.isStale(13, written.plus(Duration.ofHours(1)))).isTrue();
        assertThat(summary.isStale(11, written.plus(Duration.ofHours(7)))).isTrue();
    }
}
