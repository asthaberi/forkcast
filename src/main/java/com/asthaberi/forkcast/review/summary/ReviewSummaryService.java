package com.asthaberi.forkcast.review.summary;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Executor;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

import com.asthaberi.forkcast.common.exception.BusinessException;
import com.asthaberi.forkcast.common.exception.ErrorCode;
import com.asthaberi.forkcast.common.exception.ResourceNotFoundException;
import com.asthaberi.forkcast.menu.MenuItem;
import com.asthaberi.forkcast.menu.MenuItemRepository;
import com.asthaberi.forkcast.restaurant.Restaurant;
import com.asthaberi.forkcast.restaurant.RestaurantRepository;
import com.asthaberi.forkcast.review.RatingAggregates;
import com.asthaberi.forkcast.review.Review;
import com.asthaberi.forkcast.review.ReviewRepository;
import com.asthaberi.forkcast.review.dto.ReviewSummaryResponse;
import com.asthaberi.forkcast.review.dto.ReviewSummaryResponse.Status;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

/**
 * Serves AI review summaries, calling the LLM as rarely as possible:
 * <ul>
 *   <li>summaries are stored and reused; a page view normally costs one MongoDB read, no LLM call;</li>
 *   <li>the first request for a restaurant waits for its summary to be written;</li>
 *   <li>once enough new reviews arrive, the old summary is still returned immediately (marked stale) while a
 *       new one is written in the background (stale-while-revalidate);</li>
 *   <li>single-flight: concurrent requests for the same restaurant share one generation instead of each
 *       calling Gemini;</li>
 *   <li>after a failure (quota, outage) that restaurant isn't retried for a minute, so a broken LLM isn't hit
 *       on every page view.</li>
 * </ul>
 * Single-flight is per application instance: with several instances, each may write the same summary once.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class ReviewSummaryService {

    static final int MIN_REVIEWS = 3;
    static final Duration RETRY_AFTER_FAILURE = Duration.ofMinutes(1);
    private static final Duration WAIT_FOR_FIRST_SUMMARY = Duration.ofSeconds(30);

    private final RestaurantRepository restaurantRepository;
    private final ReviewRepository reviewRepository;
    private final MenuItemRepository menuItemRepository;
    private final ReviewSummaryRepository summaryRepository;
    private final RatingAggregates ratingAggregates;
    private final ReviewSummarizer summarizer;
    private final Clock clock;

    private final Executor executor = Executors.newVirtualThreadPerTaskExecutor();
    private final ConcurrentMap<String, CompletableFuture<ReviewSummary>> inFlight = new ConcurrentHashMap<>();
    private final ConcurrentMap<String, Instant> lastFailure = new ConcurrentHashMap<>();

    public ReviewSummaryResponse get(String restaurantId) {
        Restaurant restaurant = restaurantRepository.findById(restaurantId)
                .orElseThrow(() -> new ResourceNotFoundException("Restaurant", restaurantId));
        Map<Integer, Integer> distribution = ratingAggregates.distribution(restaurantId);
        if (restaurant.getReviewCount() < MIN_REVIEWS) {
            return new ReviewSummaryResponse(restaurantId, Status.NOT_ENOUGH_REVIEWS, restaurant.getRating(),
                    restaurant.getReviewCount(), distribution, null, List.of(), List.of(), null, List.of(), null,
                    null, null, false);
        }

        Optional<ReviewSummary> stored = summaryRepository.findById(restaurantId);
        if (stored.isEmpty()) {
            return toResponse(restaurant, distribution, awaitFirstSummary(restaurantId), false);
        }
        ReviewSummary summary = stored.get();
        boolean stale = summary.isStale(restaurant.getReviewCount(), clock.instant());
        if (stale && summarizer.isAvailable()) {
            refresh(restaurantId); // not awaited: this request gets the previous summary right away
        }
        return toResponse(restaurant, distribution, summary, stale);
    }

    private ReviewSummary awaitFirstSummary(String restaurantId) {
        if (!summarizer.isAvailable()) {
            throw new BusinessException(ErrorCode.AI_SUMMARY_UNAVAILABLE, "No Gemini API key configured");
        }
        try {
            return refresh(restaurantId).get(WAIT_FOR_FIRST_SUMMARY.toSeconds(), TimeUnit.SECONDS);
        } catch (TimeoutException e) {
            // The generation carries on and is stored; a retry will find it
            throw new BusinessException(ErrorCode.AI_SUMMARY_UNAVAILABLE,
                    "The review summary is still being written, try again shortly");
        } catch (ExecutionException e) {
            throw new BusinessException(ErrorCode.AI_SUMMARY_UNAVAILABLE,
                    "Couldn't write a review summary right now, try again later");
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new BusinessException(ErrorCode.AI_SUMMARY_UNAVAILABLE, "Interrupted while writing the summary");
        }
    }

    /** Writes a new summary in the background, or joins the one already being written for this restaurant. */
    CompletableFuture<ReviewSummary> refresh(String restaurantId) {
        Instant failedAt = lastFailure.get(restaurantId);
        if (failedAt != null && failedAt.plus(RETRY_AFTER_FAILURE).isAfter(clock.instant())) {
            return CompletableFuture.failedFuture(new IllegalStateException("Recent summary failure, not retrying yet"));
        }
        CompletableFuture<ReviewSummary> created = new CompletableFuture<>();
        CompletableFuture<ReviewSummary> running = inFlight.putIfAbsent(restaurantId, created);
        if (running != null) {
            return running;
        }
        executor.execute(() -> {
            try {
                ReviewSummary summary = generate(restaurantId);
                lastFailure.remove(restaurantId);
                created.complete(summary);
            } catch (RuntimeException e) {
                log.warn("Couldn't write review summary for restaurant {}: {}", restaurantId, e.toString());
                lastFailure.put(restaurantId, clock.instant());
                created.completeExceptionally(e);
            } finally {
                inFlight.remove(restaurantId, created);
            }
        });
        return created;
    }

    private ReviewSummary generate(String restaurantId) {
        Restaurant restaurant = restaurantRepository.findById(restaurantId)
                .orElseThrow(() -> new ResourceNotFoundException("Restaurant", restaurantId));
        // Read the count before the reviews: a review arriving in between then counts as unseen, so it
        // triggers the next refresh instead of being wrongly treated as already summarised
        int reviewCount = restaurant.getReviewCount();
        List<Review> reviews = reviewRepository.findTop40ByRestaurantIdOrderByCreatedAtDesc(restaurantId);
        List<String> dishes = menuItemRepository.findByRestaurantId(restaurantId).stream().map(MenuItem::getName).toList();

        long started = System.nanoTime();
        ReviewSummaryDraft draft = summarizer.summarize(restaurant.getName(), dishes, reviews);
        if (draft == null) {
            throw new IllegalStateException("The model returned no summary");
        }
        log.info("Wrote review summary for '{}' from {} reviews in {} ms", restaurant.getName(), reviews.size(),
                Duration.ofNanos(System.nanoTime() - started).toMillis());
        // MongoDB keeps milliseconds; truncating here makes the first response match every later read
        Instant generatedAt = clock.instant().truncatedTo(ChronoUnit.MILLIS);
        return summaryRepository.save(ReviewSummary.of(restaurantId, ReviewSummaryValidator.clean(draft, dishes),
                reviewCount, summarizer.modelName(), generatedAt));
    }

    private static ReviewSummaryResponse toResponse(Restaurant restaurant, Map<Integer, Integer> distribution,
                                                    ReviewSummary summary, boolean stale) {
        return new ReviewSummaryResponse(restaurant.getId(), Status.READY, restaurant.getRating(),
                restaurant.getReviewCount(), distribution, summary.getSummary(), summary.getPros(), summary.getCons(),
                summary.getSentiment(), summary.getMentionedDishes(), summary.getBasedOnReviews(),
                summary.getGeneratedAt(), summary.getModel(), stale);
    }
}
