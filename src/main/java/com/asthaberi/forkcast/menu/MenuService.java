package com.asthaberi.forkcast.menu;

import com.asthaberi.forkcast.common.cache.CacheNames;
import com.asthaberi.forkcast.common.exception.ResourceNotFoundException;
import com.asthaberi.forkcast.menu.dto.MenuItemRequest;
import com.asthaberi.forkcast.menu.dto.MenuItemResponse;
import com.asthaberi.forkcast.menu.dto.MenuResponse;
import com.asthaberi.forkcast.restaurant.RestaurantService;
import lombok.RequiredArgsConstructor;
import org.springframework.cache.annotation.CacheEvict;
import org.springframework.cache.annotation.Cacheable;
import org.springframework.stereotype.Service;

/**
 * Menus are read far more often than they change, so reads are cached in Redis
 * and every write evicts that restaurant's cached menu (cache-aside pattern).
 */
@Service
@RequiredArgsConstructor
public class MenuService {

    private final MenuItemRepository menuItemRepository;
    private final RestaurantService restaurantService;

    @Cacheable(cacheNames = CacheNames.MENUS, key = "#restaurantId")
    public MenuResponse getMenu(String restaurantId) {
        restaurantService.requireExists(restaurantId);
        return MenuMapper.toMenu(restaurantId, menuItemRepository.findByRestaurantId(restaurantId));
    }

    @CacheEvict(cacheNames = CacheNames.MENUS, key = "#restaurantId")
    public MenuItemResponse addItem(String restaurantId, MenuItemRequest request) {
        restaurantService.requireExists(restaurantId);
        MenuItem saved = menuItemRepository.save(MenuMapper.toEntity(restaurantId, request));
        return MenuMapper.toResponse(saved);
    }

    @CacheEvict(cacheNames = CacheNames.MENUS, key = "#restaurantId")
    public MenuItemResponse updateItem(String restaurantId, String itemId, MenuItemRequest request) {
        MenuItem item = findItem(restaurantId, itemId);
        MenuMapper.apply(request, item);
        return MenuMapper.toResponse(menuItemRepository.save(item));
    }

    @CacheEvict(cacheNames = CacheNames.MENUS, key = "#restaurantId")
    public void deleteItem(String restaurantId, String itemId) {
        menuItemRepository.delete(findItem(restaurantId, itemId));
    }

    // Looking up by id AND restaurantId stops one restaurant's URL from editing another's items.
    private MenuItem findItem(String restaurantId, String itemId) {
        return menuItemRepository.findByIdAndRestaurantId(itemId, restaurantId)
                .orElseThrow(() -> new ResourceNotFoundException("Menu item", itemId));
    }
}
