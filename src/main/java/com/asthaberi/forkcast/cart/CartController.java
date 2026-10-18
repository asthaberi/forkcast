package com.asthaberi.forkcast.cart;

import com.asthaberi.forkcast.cart.dto.AddCartItemRequest;
import com.asthaberi.forkcast.cart.dto.CartResponse;
import com.asthaberi.forkcast.cart.dto.UpdateQuantityRequest;
import com.asthaberi.forkcast.common.web.CurrentUser;
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
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * The signed-in user's cart. Every response is the full, freshly priced cart, so the client
 * never has to compute totals itself.
 */
@RestController
@RequestMapping("/api/v1/cart")
@RequiredArgsConstructor
public class CartController {

    private final CartService cartService;

    @GetMapping
    public CartResponse getCart(@CurrentUser String userId) {
        return cartService.getCart(userId);
    }

    @PostMapping("/items")
    public CartResponse addItem(@CurrentUser String userId,
                                @RequestParam(defaultValue = "false") boolean replace,
                                @Valid @RequestBody AddCartItemRequest request) {
        return cartService.addItem(userId, request, replace);
    }

    @PutMapping("/items/{menuItemId}")
    public CartResponse updateQuantity(@CurrentUser String userId, @PathVariable String menuItemId,
                                       @Valid @RequestBody UpdateQuantityRequest request) {
        return cartService.updateQuantity(userId, menuItemId, request.quantity());
    }

    @DeleteMapping("/items/{menuItemId}")
    public CartResponse removeItem(@CurrentUser String userId, @PathVariable String menuItemId) {
        return cartService.removeItem(userId, menuItemId);
    }

    @DeleteMapping
    public ResponseEntity<Void> clear(@CurrentUser String userId) {
        cartService.clear(userId);
        return ResponseEntity.noContent().build();
    }
}
