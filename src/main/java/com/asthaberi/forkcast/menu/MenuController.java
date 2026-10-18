package com.asthaberi.forkcast.menu;

import java.net.URI;

import com.asthaberi.forkcast.menu.dto.MenuItemRequest;
import com.asthaberi.forkcast.menu.dto.MenuItemResponse;
import com.asthaberi.forkcast.menu.dto.MenuResponse;
import com.asthaberi.forkcast.security.RestaurantAccess;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.security.SecurityRequirements;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.servlet.support.ServletUriComponentsBuilder;

@RestController
@Tag(name = "Menus", description = "A restaurant's menu; edits by its owner or an admin")
@RequestMapping("/api/v1/restaurants/{restaurantId}/menu")
@RequiredArgsConstructor
public class MenuController {

    private final MenuService menuService;

    @GetMapping
    @Operation(summary = "Get the menu grouped by category")
    @SecurityRequirements // public: no token needed
    public MenuResponse getMenu(@PathVariable String restaurantId) {
        return menuService.getMenu(restaurantId);
    }

    @PreAuthorize(RestaurantAccess.CAN_MANAGE)
    @PostMapping("/items")
    @Operation(summary = "Add a menu item")
    @ApiResponse(responseCode = "201", description = "Item created; Location header points to it")
    public ResponseEntity<MenuItemResponse> addItem(@PathVariable String restaurantId,
                                                    @Valid @RequestBody MenuItemRequest request) {
        MenuItemResponse created = menuService.addItem(restaurantId, request);
        URI location = ServletUriComponentsBuilder.fromCurrentRequest()
                .path("/{itemId}")
                .buildAndExpand(created.id())
                .toUri();
        return ResponseEntity.created(location).body(created);
    }

    @PreAuthorize(RestaurantAccess.CAN_MANAGE)
    @PutMapping("/items/{itemId}")
    @Operation(summary = "Replace a menu item")
    public MenuItemResponse updateItem(@PathVariable String restaurantId, @PathVariable String itemId,
                                       @Valid @RequestBody MenuItemRequest request) {
        return menuService.updateItem(restaurantId, itemId, request);
    }

    @PreAuthorize(RestaurantAccess.CAN_MANAGE)
    @DeleteMapping("/items/{itemId}")
    @Operation(summary = "Remove a menu item")
    @ApiResponse(responseCode = "204", description = "Item removed")
    public ResponseEntity<Void> deleteItem(@PathVariable String restaurantId, @PathVariable String itemId) {
        menuService.deleteItem(restaurantId, itemId);
        return ResponseEntity.noContent().build();
    }
}
