package com.asthaberi.forkcast.cart;

import com.asthaberi.forkcast.cart.dto.AddCartItemRequest;
import com.asthaberi.forkcast.cart.dto.CartResponse;
import com.asthaberi.forkcast.cart.dto.UpdateQuantityRequest;
import com.asthaberi.forkcast.common.web.CurrentUser;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.tags.Tag;
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
@Tag(name = "Cart", description = "The signed-in customer's cart, priced on every response")
@RequestMapping("/api/v1/cart")
@RequiredArgsConstructor
public class CartController {

    private final CartService cartService;

    @GetMapping
    @Operation(summary = "Get the current cart")
    public CartResponse getCart(@CurrentUser String userId) {
        return cartService.getCart(userId);
    }

    @PostMapping("/items")
    @Operation(summary = "Add an item (replace=true swaps restaurants)")
    @ApiResponse(responseCode = "409",
            description = "CART_RESTAURANT_MISMATCH (includes currentRestaurantId/Name), ITEM_UNAVAILABLE or "
                    + "CONCURRENT_MODIFICATION")
    @ApiResponse(responseCode = "422", description = "QUANTITY_LIMIT_EXCEEDED")
    public CartResponse addItem(@CurrentUser String userId,
                                @RequestParam(defaultValue = "false") boolean replace,
                                @Valid @RequestBody AddCartItemRequest request) {
        return cartService.addItem(userId, request, replace);
    }

    @PutMapping("/items/{menuItemId}")
    @Operation(summary = "Set an item's quantity (0 removes it)")
    @ApiResponse(responseCode = "422", description = "QUANTITY_LIMIT_EXCEEDED")
    public CartResponse updateQuantity(@CurrentUser String userId, @PathVariable String menuItemId,
                                       @Valid @RequestBody UpdateQuantityRequest request) {
        return cartService.updateQuantity(userId, menuItemId, request.quantity());
    }

    @DeleteMapping("/items/{menuItemId}")
    @Operation(summary = "Remove an item")
    public CartResponse removeItem(@CurrentUser String userId, @PathVariable String menuItemId) {
        return cartService.removeItem(userId, menuItemId);
    }

    @DeleteMapping
    @Operation(summary = "Empty the cart")
    @ApiResponse(responseCode = "204", description = "Cart emptied")
    public ResponseEntity<Void> clear(@CurrentUser String userId) {
        cartService.clear(userId);
        return ResponseEntity.noContent().build();
    }
}
