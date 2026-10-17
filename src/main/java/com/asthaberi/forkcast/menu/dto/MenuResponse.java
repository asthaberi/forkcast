package com.asthaberi.forkcast.menu.dto;

import java.util.List;

/**
 * A restaurant's full menu grouped by category. This is also the value stored in the Redis menu cache.
 */
public record MenuResponse(String restaurantId, int itemCount, List<MenuCategory> categories) {

    public record MenuCategory(String name, List<MenuItemResponse> items) {
    }
}
