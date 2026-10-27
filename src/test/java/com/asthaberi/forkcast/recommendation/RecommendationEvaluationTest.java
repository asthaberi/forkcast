package com.asthaberi.forkcast.recommendation;

import static com.asthaberi.forkcast.recommendation.RecommendationTestSupport.deliveredOrder;
import static com.asthaberi.forkcast.recommendation.RecommendationTestSupport.everywhere;
import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Random;
import java.util.Set;
import java.util.function.Function;
import java.util.stream.Collectors;

import com.asthaberi.forkcast.menu.MenuItem;
import com.asthaberi.forkcast.menu.MenuItemRepository;
import com.asthaberi.forkcast.order.OrderRepository;
import com.asthaberi.forkcast.recommendation.TasteProfile.DishTaste;
import com.asthaberi.forkcast.recommendation.dto.RecommendationsResponse.RecommendedDish;
import com.asthaberi.forkcast.search.DishVectorIndex;
import com.asthaberi.forkcast.search.DishVectorIndex.Neighbour;
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
 * Offline evaluation of recommendation quality, on the real Gemini vectors of the seeded menu.
 *
 * Simulated customers each have a known taste: one or two hand-labelled groups of dishes (e.g. South Indian,
 * desserts). Each orders a few dishes from their groups; the rest of their groups' dishes are what a good
 * recommender should suggest. Four strategies are compared on the same customers:
 * <ul>
 *   <li><b>averaged taste vector</b> (production, RecommendationService): one vector search from the
 *       preference-weighted mean of the liked dishes' vectors;</li>
 *   <li><b>one search per liked dish</b>, each candidate keeping its best similarity;</li>
 *   <li><b>popular</b>: the most ordered dishes, the same for everyone;</li>
 *   <li><b>random</b>: chance level.</li>
 * </ul>
 * All strategies get the same rules (no dishes already ordered, at most two per restaurant, vegetarian-only
 * when inferred), so only the ranking differs. Results go to target/recommendation-eval.md.
 */
@Testcontainers(disabledWithoutDocker = true)
@SpringBootTest(properties = {"forkcast.seed.enabled=true", "spring.cache.type=none",
        "forkcast.rate-limit.enabled=false"})
class RecommendationEvaluationTest {

    @Container
    @ServiceConnection
    static MongoDBContainer mongo = TestContainers.mongo();

    @Container
    @ServiceConnection(name = "redis")
    static GenericContainer<?> redis = TestContainers.redis();

    private static final int K = 10;
    private static final int CUSTOMERS_PER_PERSONA = 20;
    private static final String PRODUCTION = "Averaged taste vector (production)";

    // Hand-labelled taste groups (ground truth)
    private static final Map<String, List<String>> GROUPS = new LinkedHashMap<>();

    static {
        GROUPS.put("South Indian", List.of("Masala Dosa", "Mysore Masala Dosa", "Idli Sambar", "Medu Vada",
                "Filter Coffee", "Kerala Vegetable Stew", "Appam (2 pcs)"));
        GROUPS.put("Meaty Mughlai", List.of("Hyderabadi Chicken Dum Biryani", "Mutton Biryani", "Chicken 65",
                "Murgh Malai Kebab", "Butter Chicken", "Mutton Rogan Josh", "Seekh Kebab", "Tandoori Chicken (Half)",
                "Laal Maas"));
        GROUPS.put("Desserts", List.of("Gulab Jamun", "Rasmalai", "Gajar Ka Halwa", "Kaju Katli (250 g)",
                "Double Ka Meetha", "Tiramisu", "Chocolate Shake"));
        GROUPS.put("Healthy", List.of("Quinoa Buddha Bowl", "Grilled Chicken Salad", "Tofu Stir Fry Bowl",
                "Avocado Toast", "Berry Smoothie Bowl"));
        GROUPS.put("Pizza & burgers", List.of("Margherita Pizza", "Pepperoni Pizza", "Farmhouse Pizza",
                "Penne Arrabbiata", "Classic Chicken Burger", "Smoky BBQ Double Burger", "Crispy Veg Burger",
                "Peri Peri Fries"));
        GROUPS.put("Chinese & Thai", List.of("Veg Manchurian", "Chilli Chicken", "Hakka Noodles",
                "Thai Green Curry with Jasmine Rice", "Prawn Fried Rice"));
        GROUPS.put("Seafood", List.of("Goan Fish Curry", "Prawn Gassi", "Fish Fry", "Prawn Fried Rice"));
        GROUPS.put("Street food", List.of("Pani Puri", "Aloo Tikki Chaat", "Raj Kachori", "Chole Bhature",
                "Samosa"));
    }

    // Customers with one taste, and customers with two very different ones
    private static final List<List<String>> PERSONAS = List.of(
            List.of("South Indian"), List.of("Meaty Mughlai"), List.of("Desserts"), List.of("Healthy"),
            List.of("Pizza & burgers"), List.of("Chinese & Thai"), List.of("Seafood"), List.of("Street food"),
            List.of("South Indian", "Desserts"), List.of("Healthy", "Chinese & Thai"),
            List.of("Meaty Mughlai", "Desserts"), List.of("Pizza & burgers", "Street food"),
            List.of("Seafood", "South Indian"));

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
    private TasteProfiles tasteProfiles;
    @Autowired
    private PopularDishes popularDishes;
    @Autowired
    private DishVectorIndex index;
    @Autowired
    private OrderRepository orderRepository;
    @Autowired
    private MenuItemRepository menuItemRepository;

    private Map<String, MenuItem> byName;
    private Map<String, MenuItem> byId;

    @BeforeEach
    void setUp() throws InterruptedException {
        orderRepository.deleteAll();
        List<MenuItem> all = menuItemRepository.findAll();
        byName = all.stream().collect(Collectors.toMap(MenuItem::getName, Function.identity()));
        byId = all.stream().collect(Collectors.toMap(MenuItem::getId, Function.identity()));
        long deadline = System.nanoTime() + Duration.ofSeconds(30).toNanos();
        while (index.documentCount() < all.size()) {
            if (System.nanoTime() > deadline) {
                throw new AssertionError("Seeded dishes weren't indexed in time");
            }
            Thread.sleep(100);
        }
    }

    @Test
    void tasteVectorBeatsPopularityAndChanceAndKeepsBothTastes() throws IOException {
        Random random = new Random(42);
        List<Customer> customers = new ArrayList<>();
        for (List<String> persona : PERSONAS) {
            for (int i = 0; i < CUSTOMERS_PER_PERSONA; i++) {
                String userId = String.join("+", persona) + "#" + i;
                // 3 dishes for a single taste, 2 from each for two tastes
                int perGroup = persona.size() == 1 ? 3 : 2;
                Set<String> history = new HashSet<>();
                for (String group : persona) {
                    List<String> dishes = new ArrayList<>(GROUPS.get(group));
                    Collections.shuffle(dishes, random);
                    history.addAll(dishes.subList(0, perGroup));
                }
                history.forEach(name -> deliveredOrder(orderRepository, userId, byName.get(name), 1 + random.nextInt(20)));
                Map<String, Set<String>> relevant = new LinkedHashMap<>();
                for (String group : persona) {
                    Set<String> rest = new HashSet<>(GROUPS.get(group));
                    rest.removeAll(history);
                    relevant.put(group, rest);
                }
                customers.add(new Customer(userId, persona, relevant));
            }
        }

        Map<String, Function<Customer, List<String>>> strategies = new LinkedHashMap<>();
        List<ScoredPick> tasteMatches = new ArrayList<>();
        strategies.put(PRODUCTION, customer -> {
            List<RecommendedDish> forYou = recommendations.recommend(customer.userId(), everywhere(K)).forYou();
            forYou.stream().filter(dish -> dish.score() != null).forEach(dish -> tasteMatches.add(
                    new ScoredPick(dish.score(), customer.allRelevant().contains(dish.name()))));
            return forYou.stream().map(RecommendedDish::name).toList();
        });
        strategies.put("One search per liked dish (best score)", this::searchPerLikedDish);
        strategies.put("Popular", this::popular);
        strategies.put("Random", customer -> randomPicks(customer, new Random(customer.userId().hashCode())));

        Map<String, Scores> results = new LinkedHashMap<>();
        strategies.forEach((name, strategy) -> {
            Scores scores = new Scores();
            customers.forEach(customer -> scores.add(customer, strategy.apply(customer)));
            results.put(name, scores);
        });

        String report = report(results, customers.size()) + scoreBands(tasteMatches, results.get("Popular"));
        System.out.println(report);
        Files.writeString(Path.of("target", "recommendation-eval.md"), report);

        Scores production = results.get(PRODUCTION);
        Scores popular = results.get("Popular");
        Scores chance = results.get("Random");
        assertThat(production.recall(false)).isGreaterThan(popular.recall(false) + 0.3);
        assertThat(production.recall(false)).isGreaterThan(chance.recall(false) + 0.3);
        assertThat(production.recall(true)).isGreaterThan(popular.recall(true) + 0.15);
        // Averaging two unrelated tastes must not lose one of them
        assertThat(production.bothTastes()).isGreaterThanOrEqualTo(0.9);
    }

    /**
     * The alternative: a KNN search from each liked dish's own vector, each candidate keeping its best
     * similarity. Five vector queries instead of one.
     */
    private List<String> searchPerLikedDish(Customer customer) {
        TasteProfile taste = tasteProfiles.of(customer.userId());
        List<DishTaste> seeds = taste.strongest(RecommendationService.SEEDS);
        Map<String, float[]> vectors = index.vectorsOf(seeds.stream().map(DishTaste::menuItemId).toList());
        Map<String, Double> best = new HashMap<>();
        for (DishTaste seed : seeds) {
            for (Neighbour neighbour : index.nearest(vectors.get(seed.menuItemId()), RecommendationService.NEIGHBOURS,
                    restaurantIds(), taste.vegetarianOnly())) {
                best.merge(neighbour.menuItemId(), neighbour.similarity(), Math::max);
            }
        }
        List<String> ranked = best.entrySet().stream()
                .sorted(Map.Entry.<String, Double>comparingByValue().reversed())
                .map(Map.Entry::getKey)
                .toList();
        return applyRules(ranked, taste);
    }

    private List<String> popular(Customer customer) {
        TasteProfile taste = tasteProfiles.of(customer.userId());
        return applyRules(popularDishes.at(restaurantIds()).stream().map(Popularity::menuItemId).toList(),
                taste);
    }

    private List<String> randomPicks(Customer customer, Random random) {
        List<String> ids = new ArrayList<>(byId.keySet().stream().sorted().toList());
        Collections.shuffle(ids, random);
        return applyRules(ids, tasteProfiles.of(customer.userId()));
    }

    /** The same rules production applies: nothing already ordered, two per restaurant, vegetarian if inferred. */
    private List<String> applyRules(List<String> rankedIds, TasteProfile taste) {
        List<String> picked = new ArrayList<>();
        Map<String, Integer> perRestaurant = new HashMap<>();
        for (String id : rankedIds) {
            MenuItem item = byId.get(id);
            if (picked.size() == K) {
                break;
            }
            if (item == null || taste.ordered().contains(id) || (taste.vegetarianOnly() && !item.isVegetarian())
                    || perRestaurant.getOrDefault(item.getRestaurantId(), 0) >= RecommendationService.MAX_PER_RESTAURANT
                    || picked.contains(item.getName())) {
                continue;
            }
            perRestaurant.merge(item.getRestaurantId(), 1, Integer::sum);
            picked.add(item.getName());
        }
        return picked;
    }

    private Collection<String> restaurantIds() {
        return byId.values().stream().map(MenuItem::getRestaurantId).collect(Collectors.toSet());
    }

    private static String report(Map<String, Scores> results, int customers) {
        StringBuilder out = new StringBuilder(String.format(Locale.ROOT,
                "Recommendation evaluation: %d simulated customers, top %d, real Gemini vectors%n%n", customers, K));
        out.append("| Strategy | Recall@10, one taste | Recall@10, two tastes | Precision@5 | Both tastes covered |\n");
        out.append("|---|---|---|---|---|\n");
        results.forEach((name, scores) -> out.append(String.format(Locale.ROOT, "| %s | %.2f | %.2f | %.2f | %.0f%% |%n",
                name, scores.recall(false), scores.recall(true), scores.precisionAt5(), 100 * scores.bothTastes())));
        return out.toString();
    }

    /**
     * Would a minimum score help? Only if weak taste matches are less often relevant than the popular dishes
     * that would replace them.
     */
    private static String scoreBands(List<ScoredPick> picks, Scores popular) {
        double[] bounds = {0.0, 0.65, 0.70, 0.75, 0.80, 1.01};
        StringBuilder out = new StringBuilder(String.format(Locale.ROOT,
                "%nHow often a taste match is relevant, by its score (popular dishes: %.0f%% of picks relevant)%n%n"
                        + "| Score | Picks | Relevant |%n|---|---|---|%n", 100 * popular.relevantShare()));
        for (int i = 0; i < bounds.length - 1; i++) {
            double low = bounds[i];
            double high = bounds[i + 1];
            List<ScoredPick> band = picks.stream().filter(pick -> pick.score() >= low && pick.score() < high).toList();
            out.append(String.format(Locale.ROOT, "| %s | %d | %.0f%% |%n",
                    i == 0 ? "below " + high : i == bounds.length - 2 ? low + " and above" : low + " to " + high,
                    band.size(), band.isEmpty() ? 0 : 100.0 * band.stream().filter(ScoredPick::relevant).count() / band.size()));
        }
        return out.toString();
    }

    private record ScoredPick(double score, boolean relevant) {
    }

    /** @param relevant per taste group, the group's dishes the customer hasn't ordered */
    private record Customer(String userId, List<String> persona, Map<String, Set<String>> relevant) {

        Set<String> allRelevant() {
            return relevant.values().stream().flatMap(Set::stream).collect(Collectors.toSet());
        }
    }

    private static final class Scores {
        private final List<Double> recallSingle = new ArrayList<>();
        private final List<Double> recallMulti = new ArrayList<>();
        private final List<Double> precision = new ArrayList<>();
        private final List<Double> both = new ArrayList<>();
        private int picks;
        private int relevantPicks;

        void add(Customer customer, List<String> recommended) {
            Set<String> relevant = customer.allRelevant();
            long hits = recommended.stream().filter(relevant::contains).count();
            picks += recommended.size();
            relevantPicks += (int) hits;
            (customer.persona().size() == 1 ? recallSingle : recallMulti).add((double) hits / relevant.size());
            precision.add(recommended.stream().limit(5).filter(relevant::contains).count() / 5.0);
            if (customer.persona().size() > 1) {
                boolean allCovered = customer.relevant().values().stream()
                        .allMatch(group -> recommended.stream().anyMatch(group::contains));
                both.add(allCovered ? 1.0 : 0.0);
            }
        }

        double recall(boolean twoTastes) {
            return mean(twoTastes ? recallMulti : recallSingle);
        }

        double relevantShare() {
            return picks == 0 ? 0 : (double) relevantPicks / picks;
        }

        double precisionAt5() {
            return mean(precision);
        }

        double bothTastes() {
            return mean(both);
        }

        private static double mean(List<Double> values) {
            return values.stream().mapToDouble(Double::doubleValue).average().orElse(0);
        }
    }
}
