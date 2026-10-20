package com.asthaberi.forkcast.search;

import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.function.Function;
import java.util.stream.Collectors;

import com.asthaberi.forkcast.common.exception.BusinessException;
import com.asthaberi.forkcast.common.exception.ErrorCode;
import com.asthaberi.forkcast.menu.MenuItem;
import com.asthaberi.forkcast.menu.MenuItemRepository;
import com.asthaberi.forkcast.restaurant.Restaurant;
import com.asthaberi.forkcast.restaurant.RestaurantRepository;
import com.asthaberi.forkcast.restaurant.RestaurantService;
import com.asthaberi.forkcast.restaurant.dto.RestaurantResponse;
import com.asthaberi.forkcast.restaurant.dto.RestaurantSearchResponse;
import com.asthaberi.forkcast.search.dto.DishSearchResponse;
import com.asthaberi.forkcast.search.dto.DishSearchResponse.DishResult;
import lombok.RequiredArgsConstructor;
import org.springframework.ai.document.Document;
import org.springframework.ai.vectorstore.SearchRequest;
import org.springframework.ai.vectorstore.filter.FilterExpressionBuilder;
import org.springframework.ai.vectorstore.filter.FilterExpressionBuilder.Op;
import org.springframework.stereotype.Service;

/**
 * Semantic dish search: "something light and spicy for breakfast" matches dishes by meaning, not keywords.
 *
 * One Redis query does both parts: a KNN vector search over the dish embeddings, pre-filtered by exact
 * conditions on indexed metadata (available, nearby restaurant, vegetarian, price). Filtering inside the
 * vector query rather than after it means the top-K results are all usable, instead of top-K-then-discard.
 *
 * The index decides which dishes match and how well; what's shown (name, current price, availability) is
 * read from MongoDB, the source of truth. So a result never shows a stale price, and a dish deleted a moment
 * ago is skipped even if the asynchronous index update hasn't landed yet.
 */
@Service
@RequiredArgsConstructor
public class DishSearchService {

    private final DishVectorIndex index;
    private final RestaurantService restaurantService;
    private final MenuItemRepository menuItemRepository;
    private final RestaurantRepository restaurantRepository;
    private final SemanticSearchProperties properties;

    public DishSearchResponse search(DishSearchQuery query) {
        if (!index.isAvailable()) {
            throw new BusinessException(ErrorCode.AI_SEARCH_UNAVAILABLE,
                    "Semantic search isn't configured on this server (no Gemini API key)");
        }
        FilterExpressionBuilder filter = new FilterExpressionBuilder();
        Op condition = filter.eq("available", "true");

        Map<String, RestaurantResponse> nearby = Map.of();
        Double radiusKm = null;
        if (query.hasLocation()) {
            // Reuses the cached geo search, so "near me" costs no extra database work
            RestaurantSearchResponse restaurants = restaurantService.searchNearby(query.latitude(),
                    query.longitude(), query.radiusKm(), null, false);
            radiusKm = restaurants.radiusKm();
            nearby = restaurants.restaurants().stream()
                    .collect(Collectors.toMap(RestaurantResponse::id, Function.identity()));
            if (nearby.isEmpty()) {
                return new DishSearchResponse(query.text(), radiusKm, 0, List.of());
            }
            condition = filter.and(condition, filter.in("restaurantId", List.<Object>copyOf(nearby.keySet())));
        }
        if (Boolean.TRUE.equals(query.vegetarian())) {
            condition = filter.and(condition, filter.eq("vegetarian", "true"));
        }
        if (query.maxPrice() != null) {
            condition = filter.and(condition, filter.lte("price", query.maxPrice().doubleValue()));
        }

        int limit = query.limit() == null ? properties.defaultLimit() : Math.min(query.limit(), properties.maxLimit());
        // The store returns the right top-K dishes but not necessarily ranked, so sort by score here
        // (caught by DishSearchIntegrationTest comparing against brute-force nearest neighbours)
        List<Document> matches = index.store().similaritySearch(SearchRequest.builder()
                        .query(query.text())
                        .topK(limit)
                        .similarityThreshold(properties.minSimilarity())
                        .filterExpression(condition.build())
                        .build())
                .stream()
                .sorted(Comparator.comparing(Document::getScore, Comparator.nullsLast(Comparator.reverseOrder())))
                .toList();

        if (matches.isEmpty()) {
            return new DishSearchResponse(query.text(), radiusKm, 0, List.of());
        }

        // One query each for the matched dishes and their restaurants, then keep the index's ranking
        Map<String, MenuItem> items = menuItemRepository.findAllById(matches.stream().map(Document::getId).toList())
                .stream().collect(Collectors.toMap(MenuItem::getId, Function.identity()));
        Map<String, String> restaurantNames = restaurantRepository.findAllById(items.values().stream()
                        .map(MenuItem::getRestaurantId).distinct().toList())
                .stream().collect(Collectors.toMap(Restaurant::getId, Restaurant::getName));

        Map<String, RestaurantResponse> distances = nearby;
        List<DishResult> results = matches.stream()
                .map(document -> items.get(document.getId()))
                .filter(item -> item != null && item.isAvailable())
                .map(item -> toResult(item, restaurantNames.get(item.getRestaurantId()),
                        distances.get(item.getRestaurantId()), scoreOf(matches, item.getId())))
                .toList();
        return new DishSearchResponse(query.text(), radiusKm, results.size(), results);
    }

    private static double scoreOf(List<Document> matches, String id) {
        return matches.stream().filter(document -> document.getId().equals(id)).findFirst()
                .map(Document::getScore).map(score -> Math.round(score * 1000) / 1000.0).orElse(0.0);
    }

    private static DishResult toResult(MenuItem item, String restaurantName, RestaurantResponse nearbyRestaurant,
                                       double score) {
        return new DishResult(item.getId(), item.getName(), item.getDescription(), item.getCategory(),
                item.getPrice().setScale(2), item.isVegetarian(), item.getSpiceLevel().name(), item.getRestaurantId(),
                restaurantName, nearbyRestaurant == null ? null : nearbyRestaurant.distanceKm(), score);
    }
}
