package com.asthaberi.forkcast.cart;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import com.asthaberi.forkcast.common.exception.BusinessException;
import com.asthaberi.forkcast.common.exception.ErrorCode;
import com.asthaberi.forkcast.common.exception.ResourceNotFoundException;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;
import org.springframework.data.annotation.Id;
import org.springframework.data.annotation.Version;
import org.springframework.data.mongodb.core.index.Indexed;
import org.springframework.data.mongodb.core.mapping.Document;

/**
 * A user's cart: one per user, holding items from a single restaurant.
 * The business rules live here (not in the service) so they can be unit tested without Spring.
 */
@Document(collection = "carts")
@Getter
@NoArgsConstructor(access = AccessLevel.PRIVATE)
public class Cart {

    public static final int MAX_QUANTITY_PER_ITEM = 20;

    @Id
    private String id;

    // Unique, so two concurrent "first add" requests can't create two carts for one user.
    @Indexed(unique = true)
    private String userId;

    private String restaurantId;

    private String restaurantName;

    private List<CartLine> lines = new ArrayList<>();

    // Optimistic locking: every save checks the version it read is still current, and Spring Data
    // throws OptimisticLockingFailureException if another request saved in between.
    @Version
    private Long version;

    private Instant updatedAt;

    public static Cart emptyFor(String userId) {
        Cart cart = new Cart();
        cart.userId = userId;
        return cart;
    }

    public boolean isEmpty() {
        return lines.isEmpty();
    }

    public void addItem(String itemRestaurantId, String itemRestaurantName, String menuItemId, String name,
                        int quantity) {
        if (!isEmpty() && !itemRestaurantId.equals(restaurantId)) {
            throw new BusinessException(ErrorCode.CART_RESTAURANT_MISMATCH,
                    "Your cart has items from " + restaurantName + ". Clear it or add with replace=true.",
                    Map.of("currentRestaurantId", restaurantId, "currentRestaurantName", restaurantName));
        }
        restaurantId = itemRestaurantId;
        restaurantName = itemRestaurantName;

        int index = indexOf(menuItemId);
        if (index < 0) {
            checkLimit(quantity);
            lines.add(new CartLine(menuItemId, name, quantity));
        } else {
            CartLine existing = lines.get(index);
            int newQuantity = existing.quantity() + quantity;
            checkLimit(newQuantity);
            lines.set(index, existing.withQuantity(newQuantity));
        }
    }

    /** Sets an item's quantity; 0 removes it. */
    public void setQuantity(String menuItemId, int quantity) {
        int index = requireIndex(menuItemId);
        if (quantity == 0) {
            removeItem(menuItemId);
            return;
        }
        checkLimit(quantity);
        lines.set(index, lines.get(index).withQuantity(quantity));
    }

    public void removeItem(String menuItemId) {
        lines.remove(requireIndex(menuItemId));
        if (lines.isEmpty()) {
            clear();
        }
    }

    public void clear() {
        lines.clear();
        restaurantId = null;
        restaurantName = null;
    }

    void touch(Instant now) {
        updatedAt = now;
    }

    private int indexOf(String menuItemId) {
        for (int i = 0; i < lines.size(); i++) {
            if (lines.get(i).menuItemId().equals(menuItemId)) {
                return i;
            }
        }
        return -1;
    }

    private int requireIndex(String menuItemId) {
        int index = indexOf(menuItemId);
        if (index < 0) {
            throw new ResourceNotFoundException("Cart item", menuItemId);
        }
        return index;
    }

    private static void checkLimit(int quantity) {
        if (quantity > MAX_QUANTITY_PER_ITEM) {
            throw new BusinessException(ErrorCode.QUANTITY_LIMIT_EXCEEDED,
                    "At most " + MAX_QUANTITY_PER_ITEM + " of one item per order",
                    Map.of("maxQuantity", MAX_QUANTITY_PER_ITEM));
        }
    }
}
