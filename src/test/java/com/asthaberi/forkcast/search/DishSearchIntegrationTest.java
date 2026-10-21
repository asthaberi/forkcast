package com.asthaberi.forkcast.search;

import static org.assertj.core.api.Assertions.assertThat;

import java.math.BigDecimal;
import java.time.Duration;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.function.Function;
import java.util.function.BooleanSupplier;
import java.util.stream.Collectors;

import com.asthaberi.forkcast.menu.MenuItem;
import com.asthaberi.forkcast.menu.MenuItemRepository;
import com.asthaberi.forkcast.menu.MenuService;
import com.asthaberi.forkcast.menu.SpiceLevel;
import com.asthaberi.forkcast.menu.dto.MenuItemRequest;
import com.asthaberi.forkcast.restaurant.Restaurant;
import com.asthaberi.forkcast.restaurant.RestaurantRepository;
import com.asthaberi.forkcast.search.dto.DishSearchResponse;
import com.asthaberi.forkcast.search.dto.DishSearchResponse.DishResult;
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
 * The semantic search pipeline end to end against real MongoDB and Redis (vector search), with the seeded
 * 12 restaurants and 62 dishes. Only the embedding model is a local stand-in (no API key in CI).
 */
@Testcontainers(disabledWithoutDocker = true)
@SpringBootTest(properties = {"forkcast.seed.enabled=true", "spring.cache.type=none",
        "forkcast.rate-limit.enabled=false", "forkcast.ai.search.min-similarity=0.05"})
class DishSearchIntegrationTest {

    @Container
    @ServiceConnection
    static MongoDBContainer mongo = TestContainers.mongo();

    @Container
    @ServiceConnection(name = "redis")
    static GenericContainer<?> redis = TestContainers.redis();

    // Gwalior city centre, where the seeded restaurants are
    private static final double LAT = 26.2124;
    private static final double LNG = 78.1772;

    @TestConfiguration
    static class FakeEmbeddings {
        @Bean
        EmbeddingModel embeddingModel() {
            return new KeywordEmbeddingModel();
        }
    }

    @Autowired
    private DishSearchService searchService;
    @Autowired
    private DishVectorIndex index;
    @Autowired
    private MenuService menuService;
    @Autowired
    private MenuItemRepository menuItemRepository;
    @Autowired
    private RestaurantRepository restaurantRepository;

    @BeforeEach
    void waitForStartupIndexing() {
        awaitTrue(() -> index.documentCount() >= menuItemRepository.count(), "all seeded dishes indexed");
    }

    @Test
    void indexesEverySeededDishOnStartup() {
        assertThat(index.documentCount()).isEqualTo(menuItemRepository.count()).isEqualTo(62);
        assertThat(index.indexingFailures()).isZero();
    }

    @Test
    void returnsTheSameNearestDishesAsABruteForceComparison() {
        // Ground truth: cosine similarity of the query against every dish, computed directly
        Map<String, Restaurant> restaurants = restaurantRepository.findAll().stream()
                .collect(Collectors.toMap(Restaurant::getId, Function.identity()));
        float[] query = KeywordEmbeddingModel.vector("spicy chicken");
        List<String> expected = menuItemRepository.findAll().stream()
                .sorted(Comparator.comparingDouble((MenuItem item) -> -cosine(query, KeywordEmbeddingModel.vector(
                        DishDocuments.embeddingText(item, restaurants.get(item.getRestaurantId()))))))
                .limit(3)
                .map(MenuItem::getName)
                .toList();

        DishSearchResponse response = search("spicy chicken", null, null, null, null);

        // The keyword stand-in matches words, not meaning (Gemini's quality is measured in benchmark/search_eval.py);
        // what's checked here is that the Redis vector index finds the true nearest neighbours, best first
        assertThat(response.results()).extracting(DishResult::name).startsWith(expected.toArray(String[]::new));
        assertThat(response.results()).extracting(DishResult::score)
                .isSortedAccordingTo((a, b) -> Double.compare(b, a));
    }

    @Test
    void filtersAreAppliedInsideTheVectorQuery() {
        assertThat(search("chicken paneer biryani", true, null, null, null).results())
                .isNotEmpty().allMatch(DishResult::vegetarian);
        assertThat(search("chai coffee samosa snack", null, new BigDecimal("60"), null, null).results())
                .isNotEmpty().allMatch(dish -> dish.price().compareTo(new BigDecimal("60")) <= 0);
    }

    @Test
    void locationLimitsResultsToRestaurantsDeliveringThereWithDistances() {
        DishSearchResponse nearby = search("dosa thali biryani pizza", null, null, LAT, 1.0);
        assertThat(nearby.results()).isNotEmpty().allSatisfy(dish -> assertThat(dish.distanceKm()).isLessThanOrEqualTo(1.0));

        // New Delhi: no restaurant delivers there
        assertThat(search("dosa thali biryani pizza", null, null, 28.6139, 5.0).results()).isEmpty();
    }

    @Test
    void menuChangesReachTheIndexWithoutAReindex() {
        MenuItem chicken65 = menuItemRepository.findAll().stream()
                .filter(item -> item.getName().equals("Chicken 65")).findFirst().orElseThrow();
        String restaurantId = chicken65.getRestaurantId();

        // Marked sold out: no longer returned
        menuService.updateItem(restaurantId, chicken65.getId(), request(chicken65, false));
        awaitTrue(() -> namesFor("chicken 65 fried curry leaves").stream().noneMatch("Chicken 65"::equals),
                "sold-out dish leaves the results");

        // New dish: searchable shortly after it's added
        String pizzaPiazza = restaurantRepository.findByName("Pizza Piazza").orElseThrow().getId();
        menuService.addItem(pizzaPiazza, new MenuItemRequest("Truffle Mushroom Risotto",
                "Creamy arborio rice with wild mushrooms and truffle oil", "Mains", new BigDecimal("349"), true,
                SpiceLevel.NONE, List.of("rich"), true));
        awaitTrue(() -> namesFor("truffle mushroom risotto").contains("Truffle Mushroom Risotto"), "new dish indexed");

        // Deleted: gone
        MenuItem risotto = menuItemRepository.findByRestaurantId(pizzaPiazza).stream()
                .filter(item -> item.getName().equals("Truffle Mushroom Risotto")).findFirst().orElseThrow();
        menuService.deleteItem(pizzaPiazza, risotto.getId());
        awaitTrue(() -> !namesFor("truffle mushroom risotto").contains("Truffle Mushroom Risotto"), "deleted dish removed");

        // Restore for the other tests
        menuService.updateItem(restaurantId, chicken65.getId(), request(chicken65, true));
    }

    private DishSearchResponse search(String text, Boolean vegetarian, BigDecimal maxPrice, Double latitude,
                                      Double radiusKm) {
        return searchService.search(new DishSearchQuery(text, latitude, latitude == null ? null : LNG, radiusKm,
                vegetarian, maxPrice, 10));
    }

    private List<String> namesFor(String text) {
        return search(text, null, null, null, null).results().stream().map(DishResult::name).toList();
    }

    private static MenuItemRequest request(MenuItem item, boolean available) {
        return new MenuItemRequest(item.getName(), item.getDescription(), item.getCategory(), item.getPrice(),
                item.isVegetarian(), item.getSpiceLevel(), item.getTags(), available);
    }

    private static double cosine(float[] a, float[] b) {
        double dot = 0;
        for (int i = 0; i < a.length; i++) {
            dot += a[i] * b[i];
        }
        return dot; // both vectors are already unit length
    }

    private static void awaitTrue(BooleanSupplier condition, String what) {
        long deadline = System.nanoTime() + Duration.ofSeconds(20).toNanos();
        while (!condition.getAsBoolean()) {
            if (System.nanoTime() > deadline) {
                throw new AssertionError("Timed out waiting for: " + what);
            }
            try {
                Thread.sleep(100);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new AssertionError(e);
            }
        }
    }
}
