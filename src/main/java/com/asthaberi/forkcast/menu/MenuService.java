package com.asthaberi.forkcast.menu;

import com.asthaberi.forkcast.common.exception.ResourceNotFoundException;
import com.asthaberi.forkcast.menu.dto.MenuItemRequest;
import com.asthaberi.forkcast.menu.dto.MenuItemResponse;
import com.asthaberi.forkcast.menu.dto.MenuResponse;
import com.asthaberi.forkcast.restaurant.RestaurantService;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

@Service
@RequiredArgsConstructor
public class MenuService {

    private final MenuItemRepository menuItemRepository;
    private final RestaurantService restaurantService;

    public MenuResponse getMenu(String restaurantId) {
        restaurantService.requireExists(restaurantId);
        return MenuMapper.toMenu(restaurantId, menuItemRepository.findByRestaurantId(restaurantId));
    }

    public MenuItemResponse addItem(String restaurantId, MenuItemRequest request) {
        restaurantService.requireExists(restaurantId);
        MenuItem saved = menuItemRepository.save(MenuMapper.toEntity(restaurantId, request));
        return MenuMapper.toResponse(saved);
    }

    public MenuItemResponse updateItem(String restaurantId, String itemId, MenuItemRequest request) {
        MenuItem item = findItem(restaurantId, itemId);
        MenuMapper.apply(request, item);
        return MenuMapper.toResponse(menuItemRepository.save(item));
    }

    public void deleteItem(String restaurantId, String itemId) {
        menuItemRepository.delete(findItem(restaurantId, itemId));
    }

    // Looking up by id AND restaurantId stops one restaurant's URL from editing another's items.
    private MenuItem findItem(String restaurantId, String itemId) {
        return menuItemRepository.findByIdAndRestaurantId(itemId, restaurantId)
                .orElseThrow(() -> new ResourceNotFoundException("Menu item", itemId));
    }
}
