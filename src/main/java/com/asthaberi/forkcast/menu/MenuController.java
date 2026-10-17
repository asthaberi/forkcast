package com.asthaberi.forkcast.menu;

import java.net.URI;

import com.asthaberi.forkcast.menu.dto.MenuItemRequest;
import com.asthaberi.forkcast.menu.dto.MenuItemResponse;
import com.asthaberi.forkcast.menu.dto.MenuResponse;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
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
@RequestMapping("/api/v1/restaurants/{restaurantId}/menu")
@RequiredArgsConstructor
public class MenuController {

    private final MenuService menuService;

    @GetMapping
    public MenuResponse getMenu(@PathVariable String restaurantId) {
        return menuService.getMenu(restaurantId);
    }

    @PostMapping("/items")
    public ResponseEntity<MenuItemResponse> addItem(@PathVariable String restaurantId,
                                                    @Valid @RequestBody MenuItemRequest request) {
        MenuItemResponse created = menuService.addItem(restaurantId, request);
        URI location = ServletUriComponentsBuilder.fromCurrentRequest()
                .path("/{itemId}")
                .buildAndExpand(created.id())
                .toUri();
        return ResponseEntity.created(location).body(created);
    }

    @PutMapping("/items/{itemId}")
    public MenuItemResponse updateItem(@PathVariable String restaurantId, @PathVariable String itemId,
                                       @Valid @RequestBody MenuItemRequest request) {
        return menuService.updateItem(restaurantId, itemId, request);
    }

    @DeleteMapping("/items/{itemId}")
    public ResponseEntity<Void> deleteItem(@PathVariable String restaurantId, @PathVariable String itemId) {
        menuService.deleteItem(restaurantId, itemId);
        return ResponseEntity.noContent().build();
    }
}
