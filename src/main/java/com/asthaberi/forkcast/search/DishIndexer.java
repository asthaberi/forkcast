package com.asthaberi.forkcast.search;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.function.Function;
import java.util.stream.Collectors;

import com.asthaberi.forkcast.menu.MenuItem;
import com.asthaberi.forkcast.menu.MenuItemChangedEvent;
import com.asthaberi.forkcast.menu.MenuItemRepository;
import com.asthaberi.forkcast.restaurant.Restaurant;
import com.asthaberi.forkcast.restaurant.RestaurantRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.document.Document;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Component;

/**
 * Keeps the vector index in step with the menus:
 * <ul>
 *   <li>on startup, builds the index in the background if it's empty (first run, or a new index version);</li>
 *   <li>after every menu item change, re-embeds or removes just that item;</li>
 *   <li>on demand (admin endpoint), re-embeds everything.</li>
 * </ul>
 * Event handling is asynchronous, so saving a menu item never waits on the Gemini API. The trade-off: the
 * index can briefly lag a change, and a failed update is logged and fixed by the next change or a reindex.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class DishIndexer {

    // Gemini accepts up to 100 texts per batch embedding request
    private static final int BATCH_SIZE = 100;

    private final DishVectorIndex index;
    private final MenuItemRepository menuItemRepository;
    private final RestaurantRepository restaurantRepository;

    @Async
    @EventListener(ApplicationReadyEvent.class)
    public void indexOnStartupIfEmpty() {
        if (!index.isAvailable()) {
            log.info("Semantic search disabled: no Gemini API key configured (forkcast.ai.gemini.api-key)");
            return;
        }
        try {
            if (index.documentCount() == 0) {
                log.info("Search index is empty; indexing all dishes in the background");
                reindexAll();
            }
        } catch (RuntimeException e) {
            log.warn("Initial search indexing failed; run POST /api/v1/admin/search/reindex later: {}", e.toString());
        }
    }

    /** Embeds and (re)writes every menu item. Returns the number indexed. */
    public int reindexAll() {
        Map<String, Restaurant> restaurants = restaurantRepository.findAll().stream()
                .collect(Collectors.toMap(Restaurant::getId, Function.identity()));
        List<Document> documents = new ArrayList<>();
        for (MenuItem item : menuItemRepository.findAll()) {
            Restaurant restaurant = restaurants.get(item.getRestaurantId());
            if (restaurant != null) {
                documents.add(DishDocuments.toDocument(item, restaurant));
            }
        }
        for (int from = 0; from < documents.size(); from += BATCH_SIZE) {
            index.store().add(documents.subList(from, Math.min(from + BATCH_SIZE, documents.size())));
        }
        log.info("Indexed {} dishes", documents.size());
        return documents.size();
    }

    @Async
    @EventListener
    public void onMenuItemChanged(MenuItemChangedEvent event) {
        if (!index.isAvailable()) {
            return;
        }
        try {
            if (event.deleted()) {
                index.store().delete(List.of(event.menuItemId()));
                return;
            }
            menuItemRepository.findById(event.menuItemId())
                    .flatMap(item -> restaurantRepository.findById(item.getRestaurantId())
                            .map(restaurant -> DishDocuments.toDocument(item, restaurant)))
                    .ifPresent(document -> index.store().add(List.of(document)));
        } catch (RuntimeException e) {
            log.warn("Couldn't update search index for menu item {}: {}", event.menuItemId(), e.toString());
        }
    }
}
