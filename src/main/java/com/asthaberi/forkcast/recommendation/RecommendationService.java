package com.asthaberi.forkcast.recommendation;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Function;
import java.util.stream.Collectors;

import com.asthaberi.forkcast.menu.MenuItem;
import com.asthaberi.forkcast.menu.MenuItemRepository;
import com.asthaberi.forkcast.recommendation.TasteProfile.DishTaste;
import com.asthaberi.forkcast.recommendation.dto.RecommendationsResponse;
import com.asthaberi.forkcast.recommendation.dto.RecommendationsResponse.RecommendedDish;
import com.asthaberi.forkcast.recommendation.dto.RecommendationsResponse.Strategy;
import com.asthaberi.forkcast.restaurant.RestaurantService;
import com.asthaberi.forkcast.restaurant.dto.RestaurantResponse;
import com.asthaberi.forkcast.restaurant.dto.RestaurantSearchResponse;
import com.asthaberi.forkcast.search.DishVectorIndex;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

/**
 * Personal dish recommendations, built from three sources:
 * <ol>
 *   <li><b>Order again:</b> the customer's favourites (TasteProfile), if available and delivered to them now.</li>
 *   <li><b>For you:</b> dishes they haven't tried that are close in meaning to what they like. Their strongest
 *       preferences are combined into one <i>taste vector</i>, the preference-weighted mean of those dishes'
 *       stored Gemini vectors, and one KNN query in Redis finds the dishes nearest to it. Each pick is then
 *       explained by the liked dish it's closest to ("Because you ordered Masala Dosa").</li>
 *   <li><b>Popular near you,</b> then dishes from top-rated restaurants: for customers with no history and to
 *       fill up the list.</li>
 * </ol>
 * At most two dishes per restaurant are recommended, so one restaurant can't fill the whole list.
 *
 * Why one averaged vector rather than a search per liked dish: measured on real Gemini vectors
 * (RecommendationEvaluationTest), averaging matched or beat per-dish searches, also for customers with two
 * unrelated tastes, with one vector query instead of five. With cosine similarity, the dishes nearest to the
 * mean are those with the highest weighted sum of similarities to everything the customer likes.
 *
 * No Gemini call happens here: the dish vectors were computed when the menu was indexed, so recommendations
 * cost a handful of MongoDB and Redis queries.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class RecommendationService {

    static final int SEEDS = 5;
    static final int NEIGHBOURS = 40;
    static final int MAX_PER_RESTAURANT = 2;
    static final int ORDER_AGAIN_LIMIT = 5;
    private static final int TOP_RATED_RESTAURANTS = 5;
    private static final String MATCHES_YOUR_TASTE = "Matches your taste";

    private final RestaurantService restaurantService;
    private final TasteProfiles tasteProfiles;
    private final PopularDishes popularDishes;
    private final DishVectorIndex index;
    private final MenuItemRepository menuItemRepository;

    public RecommendationsResponse recommend(String userId, RecommendationQuery query) {
        RestaurantSearchResponse search = restaurantService.searchNearby(query.latitude(), query.longitude(),
                query.radiusKm(), null, query.openNow());
        Map<String, RestaurantResponse> nearby = search.restaurants().stream()
                .collect(Collectors.toMap(RestaurantResponse::id, Function.identity()));
        TasteProfile taste = tasteProfiles.of(userId);
        if (nearby.isEmpty()) {
            return new RecommendationsResponse(Strategy.POPULAR, search.radiusKm(), taste.vegetarianOnly(), List.of(),
                    List.of());
        }

        TasteMatch match = tasteMatch(taste, nearby.keySet());
        List<Candidate> candidates = new ArrayList<>(match.candidates());
        popularDishes.at(nearby.keySet()).forEach(popular -> candidates.add(new Candidate(popular.menuItemId(),
                popular.customers() == 1 ? "Ordered near you this month"
                        : "Ordered by " + popular.customers() + " people near you this month", null)));

        // One query for every dish that might be shown
        Set<String> ids = new HashSet<>(taste.liked().keySet());
        candidates.forEach(candidate -> ids.add(candidate.menuItemId()));
        Map<String, MenuItem> items = menuItemRepository.findAllById(ids).stream()
                .collect(Collectors.toMap(MenuItem::getId, Function.identity(), (a, b) -> a, HashMap::new));

        List<RecommendedDish> forYou = pick(candidates, items, taste, nearby, query.limit());
        if (forYou.size() < query.limit()) {
            forYou = pick(withTopRated(candidates, items, nearby), items, taste, nearby, query.limit());
        }
        return new RecommendationsResponse(match.candidates().isEmpty() ? Strategy.POPULAR : Strategy.PERSONALIZED,
                search.radiusKm(), taste.vegetarianOnly(), orderAgain(taste, items, nearby), explain(forYou, match));
    }

    /** Dishes nearest to the customer's taste vector, best first; none without history or an index. */
    TasteMatch tasteMatch(TasteProfile taste, Set<String> restaurantIds) {
        if (taste.isEmpty() || !index.isAvailable()) {
            return TasteMatch.NONE;
        }
        List<DishTaste> seeds = taste.strongest(SEEDS);
        try {
            Map<String, float[]> seedVectors = index.vectorsOf(seeds.stream().map(DishTaste::menuItemId).toList());
            if (seedVectors.isEmpty()) {
                return TasteMatch.NONE; // liked dishes no longer on any menu
            }
            float[] tasteVector = Vectors.weightedMean(seeds.stream()
                    .filter(seed -> seedVectors.containsKey(seed.menuItemId()))
                    .map(seed -> new Vectors.Weighted(seedVectors.get(seed.menuItemId()), seed.weight()))
                    .toList());
            // The nearest dishes include ones already ordered, which are skipped, so ask for that many more
            int k = NEIGHBOURS + Math.min(taste.ordered().size(), 30);
            List<Candidate> candidates = index.nearest(tasteVector, k, restaurantIds, taste.vegetarianOnly()).stream()
                    .filter(neighbour -> !taste.ordered().contains(neighbour.menuItemId()))
                    .map(neighbour -> new Candidate(neighbour.menuItemId(), MATCHES_YOUR_TASTE, neighbour.similarity()))
                    .toList();
            return new TasteMatch(candidates, seeds, seedVectors);
        } catch (RuntimeException e) {
            // Redis or the index unavailable: recommendations still work, from popularity
            log.warn("Personal recommendations unavailable, using popular dishes: {}", e.toString());
            return TasteMatch.NONE;
        }
    }

    /** "Because you ordered X": for each personal pick, the liked dish whose vector is closest to it. */
    private List<RecommendedDish> explain(List<RecommendedDish> dishes, TasteMatch match) {
        List<String> personal = dishes.stream().filter(dish -> dish.score() != null)
                .map(RecommendedDish::menuItemId).toList();
        if (personal.isEmpty()) {
            return dishes;
        }
        try {
            Map<String, float[]> vectors = index.vectorsOf(personal);
            return dishes.stream().map(dish -> {
                float[] vector = vectors.get(dish.menuItemId());
                if (dish.score() == null || vector == null) {
                    return dish;
                }
                return match.seeds().stream()
                        .filter(seed -> match.seedVectors().containsKey(seed.menuItemId()))
                        .max(Comparator.comparingDouble(seed ->
                                Vectors.cosine(match.seedVectors().get(seed.menuItemId()), vector)))
                        .map(seed -> dish.withReason("Because you ordered " + seed.name()))
                        .orElse(dish);
            }).toList();
        } catch (RuntimeException e) {
            log.warn("Couldn't explain recommendations: {}", e.toString());
            return dishes; // still "Matches your taste"
        }
    }

    /** Goes through candidates in order, keeping those that can be ordered, at most two per restaurant. */
    private static List<RecommendedDish> pick(List<Candidate> candidates, Map<String, MenuItem> items,
                                              TasteProfile taste, Map<String, RestaurantResponse> nearby, int limit) {
        List<RecommendedDish> picked = new ArrayList<>();
        Set<String> seen = new LinkedHashSet<>();
        Map<String, Integer> perRestaurant = new HashMap<>();
        for (Candidate candidate : candidates) {
            MenuItem item = items.get(candidate.menuItemId());
            if (picked.size() == limit) {
                break;
            }
            if (item == null || !item.isAvailable() || !nearby.containsKey(item.getRestaurantId())
                    || !seen.add(item.getId()) || taste.ordered().contains(item.getId())
                    || (taste.vegetarianOnly() && !item.isVegetarian())
                    || perRestaurant.getOrDefault(item.getRestaurantId(), 0) >= MAX_PER_RESTAURANT) {
                continue;
            }
            perRestaurant.merge(item.getRestaurantId(), 1, Integer::sum);
            picked.add(toDish(item, nearby.get(item.getRestaurantId()), candidate.reason(), candidate.score()));
        }
        return picked;
    }

    /** Last resort for a new area with no orders yet: dishes from the best-rated restaurants nearby. */
    private List<Candidate> withTopRated(List<Candidate> candidates, Map<String, MenuItem> items,
                                         Map<String, RestaurantResponse> nearby) {
        List<String> topRated = nearby.values().stream()
                .sorted(Comparator.comparingDouble(RestaurantResponse::rating).reversed())
                .limit(TOP_RATED_RESTAURANTS)
                .map(RestaurantResponse::id)
                .toList();
        List<Candidate> extended = new ArrayList<>(candidates);
        List<MenuItem> dishes = menuItemRepository.findByRestaurantIdInAndAvailableTrue(topRated);
        dishes.sort(Comparator.comparingInt(item -> topRated.indexOf(item.getRestaurantId())));
        for (MenuItem dish : dishes) {
            items.put(dish.getId(), dish);
            RestaurantResponse restaurant = nearby.get(dish.getRestaurantId());
            extended.add(new Candidate(dish.getId(), "From " + restaurant.name() + ", rated " + restaurant.rating(), null));
        }
        return extended;
    }

    private static List<RecommendedDish> orderAgain(TasteProfile taste, Map<String, MenuItem> items,
                                                    Map<String, RestaurantResponse> nearby) {
        return taste.strongest(Integer.MAX_VALUE).stream()
                .filter(dish -> {
                    MenuItem item = items.get(dish.menuItemId());
                    return item != null && item.isAvailable() && nearby.containsKey(item.getRestaurantId());
                })
                .limit(ORDER_AGAIN_LIMIT)
                .map(dish -> {
                    MenuItem item = items.get(dish.menuItemId());
                    String reason = dish.timesOrdered() == 1 ? "You ordered this before"
                            : "You've ordered this " + dish.timesOrdered() + " times";
                    return toDish(item, nearby.get(item.getRestaurantId()), reason, null);
                })
                .toList();
    }

    private static RecommendedDish toDish(MenuItem item, RestaurantResponse restaurant, String reason, Double score) {
        return new RecommendedDish(item.getId(), item.getName(), item.getCategory(), item.getPrice().setScale(2),
                item.isVegetarian(), item.getRestaurantId(), restaurant.name(), restaurant.distanceKm(),
                restaurant.openNow(), reason, score == null ? null : Math.round(score * 1000) / 1000.0);
    }

    /** @param score null for popularity-based candidates */
    record Candidate(String menuItemId, String reason, Double score) {
    }

    /** Personal candidates plus the liked dishes they came from, kept for explaining the final picks. */
    record TasteMatch(List<Candidate> candidates, List<DishTaste> seeds, Map<String, float[]> seedVectors) {
        static final TasteMatch NONE = new TasteMatch(List.of(), List.of(), Map.of());
    }
}
