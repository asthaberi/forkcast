package com.asthaberi.forkcast.recommendation;

import static com.asthaberi.forkcast.recommendation.RecommendationTestSupport.deliveredOrder;
import static com.asthaberi.forkcast.recommendation.RecommendationTestSupport.everywhere;
import static org.assertj.core.api.Assertions.assertThat;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Function;
import java.util.stream.Collectors;

import com.asthaberi.forkcast.menu.MenuItem;
import com.asthaberi.forkcast.menu.MenuItemRepository;
import com.asthaberi.forkcast.order.Order;
import com.asthaberi.forkcast.order.OrderRepository;
import com.asthaberi.forkcast.recommendation.dto.RecommendationsResponse;
import com.asthaberi.forkcast.recommendation.dto.RecommendationsResponse.RecommendedDish;
import com.asthaberi.forkcast.recommendation.dto.RecommendationsResponse.Strategy;
import com.asthaberi.forkcast.review.Review;
import com.asthaberi.forkcast.review.ReviewRepository;
import com.asthaberi.forkcast.search.DishVectorIndex;
import com.asthaberi.forkcast.search.GeminiFixtureEmbeddingModel;
import com.asthaberi.forkcast.support.TestContainers;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.ai.embedding.EmbeddingModel;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.context.annotation.Bean;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.mongodb.MongoDBContainer;

/**
 * Recommendations end to end: seeded restaurants and menus in MongoDB, dish vectors in Redis vector search,
 * using the recorded real Gemini embeddings, so "similar dishes" means what it means in production.
 */
@Testcontainers(disabledWithoutDocker = true)
@SpringBootTest(properties = {"forkcast.seed.enabled=true", "spring.cache.type=none",
        "forkcast.rate-limit.enabled=false"})
class RecommendationIntegrationTest {

    @Container
    @ServiceConnection
    static MongoDBContainer mongo = TestContainers.mongo();

    @Container
    @ServiceConnection(name = "redis")
    static GenericContainer<?> redis = TestContainers.redis();

    private static final Set<String> SOUTH_INDIAN = Set.of("Mysore Masala Dosa", "Medu Vada", "Filter Coffee",
            "Kerala Vegetable Stew", "Appam (2 pcs)");

    @TestConfiguration
    static class RecordedGeminiEmbeddings {
        @Bean
        EmbeddingModel embeddingModel() {
            return new GeminiFixtureEmbeddingModel();
        }
    }

    @Autowired
    private RecommendationService recommendations;
    @Autowired
    private OrderRepository orderRepository;
    @Autowired
    private ReviewRepository reviewRepository;
    @Autowired
    private MenuItemRepository menuItemRepository;
    @Autowired
    private DishVectorIndex index;

    private Map<String, MenuItem> dishes;

    @BeforeEach
    void setUp() throws InterruptedException {
        orderRepository.deleteAll();
        reviewRepository.deleteAll();
        dishes = menuItemRepository.findAll().stream().collect(Collectors.toMap(MenuItem::getName, Function.identity()));
        long deadline = System.nanoTime() + Duration.ofSeconds(30).toNanos();
        while (index.documentCount() < dishes.size()) { // the startup indexing runs in the background
            if (System.nanoTime() > deadline) {
                throw new AssertionError("Seeded dishes weren't indexed in time");
            }
            Thread.sleep(100);
        }
    }

    @Test
    void aNewCustomerGetsWhatPeopleNearbyOrderAtMostTwoPerRestaurant() {
        for (String user : List.of("a", "b", "c")) {
            order(user, "Butter Chicken");
        }
        order("a", "Masala Dosa");
        order("b", "Masala Dosa");

        RecommendationsResponse response = recommendations.recommend("new-customer", everywhere(10));

        assertThat(response.strategy()).isEqualTo(Strategy.POPULAR);
        assertThat(response.orderAgain()).isEmpty();
        assertThat(response.forYou()).hasSize(10);
        assertThat(response.forYou().get(0).name()).isEqualTo("Butter Chicken");
        assertThat(response.forYou().get(0).reason()).isEqualTo("Ordered by 3 people near you this month");
        assertThat(response.forYou().get(1).name()).isEqualTo("Masala Dosa");
        // Topped up from the best-rated restaurants once popular dishes run out
        assertThat(response.forYou().get(2).reason()).startsWith("From ");
        assertAtMostTwoPerRestaurant(response.forYou());
    }

    @Test
    void recommendsNewDishesLikeTheOnesYouOrderedAndExplainsWhy() {
        order("me", "Masala Dosa");
        order("me", "Idli Sambar");

        RecommendationsResponse response = recommendations.recommend("me", everywhere(10));

        assertThat(response.strategy()).isEqualTo(Strategy.PERSONALIZED);
        assertThat(response.orderAgain()).extracting(RecommendedDish::name)
                .containsExactlyInAnyOrder("Masala Dosa", "Idli Sambar");
        assertThat(response.forYou()).extracting(RecommendedDish::name)
                .doesNotContain("Masala Dosa", "Idli Sambar")
                .hasSize(10);
        RecommendedDish first = response.forYou().getFirst();
        assertThat(SOUTH_INDIAN).contains(first.name());
        assertThat(first.reason()).isIn("Because you ordered Masala Dosa", "Because you ordered Idli Sambar");
        assertThat(first.score()).isNotNull();
        // Weak matches don't claim to be "because you ordered" something
        assertThat(response.forYou()).allSatisfy(dish -> assertThat(dish.reason()).startsWith(
                dish.score() < RecommendationService.STRONG_MATCH ? "Something different to try" : "Because you ordered"));
        assertAtMostTwoPerRestaurant(response.forYou());
    }

    @Test
    void anAllVegetarianHistoryOnlyGetsVegetarianDishes() {
        for (String dish : List.of("Paneer Tikka", "Dal Makhani", "Masala Dosa", "Pani Puri", "Gulab Jamun")) {
            order("veg", dish);
        }

        RecommendationsResponse response = recommendations.recommend("veg", everywhere(10));

        assertThat(response.vegetarianOnly()).isTrue();
        assertThat(response.forYou()).isNotEmpty().allSatisfy(dish -> assertThat(dish.vegetarian()).isTrue());
    }

    @Test
    void aDishReviewedBadlyIsNeitherOrderedAgainNorRecommended() {
        Order bad = order("picky", "Chilli Chicken");
        Order good = order("picky", "Hakka Noodles");
        review("picky", bad, 1);
        review("picky", good, 5);

        RecommendationsResponse response = recommendations.recommend("picky", everywhere(10));

        assertThat(response.orderAgain()).extracting(RecommendedDish::name).containsExactly("Hakka Noodles");
        assertThat(response.forYou()).extracting(RecommendedDish::name).doesNotContain("Chilli Chicken");
    }

    @Test
    void aDishTakenOffTheMenuIsNotRecommendedEvenBeforeTheIndexCatchesUp() {
        order("me", "Masala Dosa");
        MenuItem mysore = dishes.get("Mysore Masala Dosa");
        mysore.setAvailable(false);
        // Saved straight to MongoDB: the vector index still lists it as available
        menuItemRepository.save(mysore);
        try {
            RecommendationsResponse response = recommendations.recommend("me", everywhere(10));

            assertThat(response.forYou()).extracting(RecommendedDish::name).doesNotContain("Mysore Masala Dosa");
        } finally {
            mysore.setAvailable(true);
            menuItemRepository.save(mysore);
        }
    }

    private Order order(String userId, String dishName) {
        return deliveredOrder(orderRepository, userId, RecommendationTestSupport.dish(dishes, dishName), 1);
    }

    private void review(String userId, Order order, int stars) {
        reviewRepository.save(Review.of(order.getRestaurantId(), order.getId(), userId, "Test U.", stars, "review",
                Instant.now()));
    }

    private static void assertAtMostTwoPerRestaurant(List<RecommendedDish> dishes) {
        assertThat(dishes.stream().collect(Collectors.groupingBy(RecommendedDish::restaurantId, Collectors.counting()))
                .values()).allSatisfy(count -> assertThat(count).isLessThanOrEqualTo(2));
    }
}
