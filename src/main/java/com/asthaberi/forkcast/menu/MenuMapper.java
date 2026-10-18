package com.asthaberi.forkcast.menu;

import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.stream.Collectors;

import com.asthaberi.forkcast.menu.dto.MenuItemRequest;
import com.asthaberi.forkcast.menu.dto.MenuItemResponse;
import com.asthaberi.forkcast.menu.dto.MenuResponse;
import com.asthaberi.forkcast.menu.dto.MenuResponse.MenuCategory;

final class MenuMapper {

    private MenuMapper() {
    }

    static MenuItem toEntity(String restaurantId, MenuItemRequest request) {
        MenuItem item = MenuItem.builder().restaurantId(restaurantId).build();
        apply(request, item);
        return item;
    }

    /** Copies request fields onto an existing item (used by both create and full update). */
    static void apply(MenuItemRequest request, MenuItem item) {
        item.setName(request.name().trim());
        item.setDescription(request.description() == null ? null : request.description().trim());
        item.setCategory(request.category().trim());
        item.setPrice(request.price());
        item.setVegetarian(request.vegetarian());
        item.setSpiceLevel(request.spiceLevel() == null ? SpiceLevel.NONE : request.spiceLevel());
        item.setTags(request.tags() == null ? List.of() : request.tags().stream().map(String::trim).toList());
        item.setAvailable(request.available() == null || request.available());
    }

    static MenuItemResponse toResponse(MenuItem item) {
        return new MenuItemResponse(item.getId(), item.getRestaurantId(), item.getName(), item.getDescription(),
                item.getCategory(), item.getPrice(), item.isVegetarian(), item.getSpiceLevel(), item.getTags(),
                item.isAvailable());
    }

    /** Groups items by category (alphabetical), with items sorted by name inside each category. */
    static MenuResponse toMenu(String restaurantId, List<MenuItem> items) {
        Map<String, List<MenuItemResponse>> byCategory = items.stream()
                .sorted(Comparator.comparing(MenuItem::getName))
                .collect(Collectors.groupingBy(MenuItem::getCategory, TreeMap::new,
                        Collectors.mapping(MenuMapper::toResponse, Collectors.toList())));

        List<MenuCategory> categories = byCategory.entrySet().stream()
                .map(entry -> new MenuCategory(entry.getKey(), entry.getValue()))
                .toList();
        return new MenuResponse(restaurantId, items.size(), categories);
    }
}
