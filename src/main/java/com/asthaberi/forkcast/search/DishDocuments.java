package com.asthaberi.forkcast.search;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

import com.asthaberi.forkcast.menu.MenuItem;
import com.asthaberi.forkcast.menu.SpiceLevel;
import com.asthaberi.forkcast.restaurant.Restaurant;
import org.springframework.ai.document.Document;

/** Turns a menu item into the text that gets embedded plus the metadata that search filters on. */
final class DishDocuments {

    private DishDocuments() {
    }

    static Document toDocument(MenuItem item, Restaurant restaurant) {
        // Only the fields search filters on; what's displayed is read fresh from MongoDB (see DishSearchService)
        Map<String, Object> metadata = new HashMap<>();
        metadata.put("restaurantId", item.getRestaurantId());
        metadata.put("price", item.getPrice().doubleValue());
        // Tag fields hold strings
        metadata.put("vegetarian", String.valueOf(item.isVegetarian()));
        metadata.put("available", String.valueOf(item.isAvailable()));
        metadata.put("spiceLevel", item.getSpiceLevel().name());
        // The document id is the menu item id, so re-indexing an item overwrites its previous entry
        return new Document(item.getId(), embeddingText(item, restaurant), metadata);
    }

    /**
     * Everything that describes the dish in plain words, so a query like "something spicy and vegetarian
     * for breakfast" can match on meaning. Price stays out: it's a filter, not something similar or not.
     */
    static String embeddingText(MenuItem item, Restaurant restaurant) {
        List<String> parts = new ArrayList<>();
        parts.add(item.getName() + ".");
        if (item.getDescription() != null && !item.getDescription().isBlank()) {
            parts.add(item.getDescription());
        }
        parts.add("Category: " + item.getCategory() + ".");
        parts.add("Cuisine: " + String.join(", ", restaurant.getCuisines()) + ".");
        parts.add(item.isVegetarian() ? "Vegetarian." : "Non-vegetarian.");
        parts.add(spice(item.getSpiceLevel()));
        if (item.getTags() != null && !item.getTags().isEmpty()) {
            parts.add("Tags: " + String.join(", ", item.getTags()) + ".");
        }
        parts.add("From " + restaurant.getName() + ".");
        return String.join(" ", parts);
    }

    private static String spice(SpiceLevel level) {
        return switch (level) {
            case NONE -> "Not spicy.";
            default -> level.name().toLowerCase(Locale.ROOT) + " spicy.";
        };
    }
}
