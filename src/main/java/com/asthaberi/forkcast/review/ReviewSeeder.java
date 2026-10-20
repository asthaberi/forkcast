package com.asthaberi.forkcast.review;

import java.io.IOException;
import java.io.InputStream;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.List;

import com.asthaberi.forkcast.restaurant.RestaurantRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.core.annotation.Order;
import org.springframework.core.io.ClassPathResource;
import org.springframework.stereotype.Component;
import tools.jackson.core.type.TypeReference;
import tools.jackson.databind.json.JsonMapper;

/**
 * Loads sample reviews so ratings and AI summaries have something to work with locally. Seeded reviews have
 * no order, so they show as not verified. One of them is a deliberate prompt-injection attempt, used to check
 * that the summarizer doesn't obey instructions written inside reviews.
 */
@Slf4j
@Component
@ConditionalOnProperty(name = "forkcast.seed.enabled", havingValue = "true")
@Order(4)
@RequiredArgsConstructor
class ReviewSeeder implements ApplicationRunner {

    private static final String SEED_FILE = "data/reviews.json";

    private final ReviewRepository reviewRepository;
    private final RestaurantRepository restaurantRepository;
    private final RatingAggregates ratingAggregates;
    private final JsonMapper jsonMapper;
    private final Clock clock;

    @Override
    public void run(ApplicationArguments args) throws IOException {
        if (reviewRepository.count() > 0) {
            log.info("Reviews already present, skipping seed");
            return;
        }
        try (InputStream in = new ClassPathResource(SEED_FILE).getInputStream()) {
            List<RestaurantReviews> seeds = jsonMapper.readValue(in, new TypeReference<>() {
            });
            Instant now = clock.instant();
            int count = 0;
            for (RestaurantReviews seed : seeds) {
                var restaurant = restaurantRepository.findByName(seed.restaurant());
                if (restaurant.isEmpty()) {
                    log.warn("Skipping reviews for unknown restaurant '{}'", seed.restaurant());
                    continue;
                }
                String restaurantId = restaurant.get().getId();
                reviewRepository.saveAll(seed.reviews().stream()
                        .map(review -> Review.of(restaurantId, null, null, review.author(), review.rating(),
                                review.text(), now.minus(Duration.ofDays(review.daysAgo()))))
                        .toList());
                // The seeded rating is replaced by the real average of these reviews
                ratingAggregates.recompute(restaurantId);
                count += seed.reviews().size();
            }
            log.info("Seeded {} reviews from {}", count, SEED_FILE);
        }
    }

    record RestaurantReviews(String restaurant, List<ReviewSeed> reviews) {
    }

    record ReviewSeed(String author, int rating, int daysAgo, String text) {
    }
}
