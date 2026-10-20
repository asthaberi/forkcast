package com.asthaberi.forkcast.review.summary;

import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.function.Function;
import java.util.stream.Collectors;

import com.asthaberi.forkcast.review.summary.ReviewSummaryDraft.Sentiment;

/**
 * Checks and trims what the model returned before it's stored or shown. The model is told the rules, but
 * output is never trusted blindly:
 * <ul>
 *   <li>dish names must exist on the restaurant's menu, so a hallucinated dish is dropped (and spelling
 *       is normalised to the menu's);</li>
 *   <li>list sizes and text lengths are capped, so the UI can't be flooded;</li>
 *   <li>a missing sentiment becomes MIXED rather than failing the whole summary.</li>
 * </ul>
 */
final class ReviewSummaryValidator {

    static final int MAX_ITEMS = 4;
    static final int MAX_SUMMARY_CHARS = 400;
    static final int MAX_POINT_CHARS = 100;

    private ReviewSummaryValidator() {
    }

    static ReviewSummaryDraft clean(ReviewSummaryDraft draft, List<String> menuDishes) {
        Map<String, String> menuByLowerName = menuDishes.stream()
                .collect(Collectors.toMap(name -> name.toLowerCase(Locale.ROOT), Function.identity(), (a, b) -> a));
        List<String> groundedDishes = nonNull(draft.mentionedDishes()).stream()
                .map(dish -> menuByLowerName.get(dish.trim().toLowerCase(Locale.ROOT)))
                .filter(Objects::nonNull)
                .distinct()
                .limit(MAX_ITEMS)
                .toList();
        return new ReviewSummaryDraft(
                truncate(draft.summary() == null ? "" : draft.summary(), MAX_SUMMARY_CHARS),
                points(draft.pros()),
                points(draft.cons()),
                draft.sentiment() == null ? Sentiment.MIXED : draft.sentiment(),
                groundedDishes);
    }

    private static List<String> points(List<String> points) {
        return nonNull(points).stream()
                .filter(point -> point != null && !point.isBlank())
                .map(point -> truncate(point, MAX_POINT_CHARS))
                .limit(MAX_ITEMS)
                .toList();
    }

    private static List<String> nonNull(List<String> list) {
        return list == null ? List.of() : list;
    }

    private static String truncate(String text, int max) {
        String trimmed = text.trim();
        return trimmed.length() <= max ? trimmed : trimmed.substring(0, max - 1) + "…";
    }
}
