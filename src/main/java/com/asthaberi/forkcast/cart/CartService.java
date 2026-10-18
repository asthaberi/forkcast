package com.asthaberi.forkcast.cart;

import java.time.Clock;
import java.util.concurrent.ThreadLocalRandom;
import java.util.function.Consumer;

import com.asthaberi.forkcast.cart.dto.AddCartItemRequest;
import com.asthaberi.forkcast.cart.dto.CartResponse;
import com.asthaberi.forkcast.common.exception.BusinessException;
import com.asthaberi.forkcast.common.exception.ErrorCode;
import com.asthaberi.forkcast.menu.MenuService;
import com.asthaberi.forkcast.menu.dto.MenuItemResponse;
import com.asthaberi.forkcast.restaurant.RestaurantService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.dao.OptimisticLockingFailureException;
import org.springframework.stereotype.Service;

@Slf4j
@Service
@RequiredArgsConstructor
public class CartService {

    static final int MAX_ATTEMPTS = 5;

    private final CartRepository cartRepository;
    private final MenuService menuService;
    private final RestaurantService restaurantService;
    private final CartPricer cartPricer;
    private final Clock clock;

    public CartResponse getCart(String userId) {
        return cartPricer.price(loadOrCreate(userId));
    }

    /**
     * @param replace when true, clears a cart holding another restaurant's items instead of rejecting the add
     */
    public CartResponse addItem(String userId, AddCartItemRequest request, boolean replace) {
        MenuItemResponse item = menuService.getItem(request.menuItemId());
        if (!item.available()) {
            throw new BusinessException(ErrorCode.ITEM_UNAVAILABLE, item.name() + " is currently unavailable");
        }
        String restaurantName = restaurantService.getById(item.restaurantId()).name();

        return update(userId, cart -> {
            if (replace && !item.restaurantId().equals(cart.getRestaurantId())) {
                cart.clear();
            }
            cart.addItem(item.restaurantId(), restaurantName, item.id(), item.name(), request.quantity());
        });
    }

    public CartResponse updateQuantity(String userId, String menuItemId, int quantity) {
        return update(userId, cart -> cart.setQuantity(menuItemId, quantity));
    }

    public CartResponse removeItem(String userId, String menuItemId) {
        return update(userId, cart -> cart.removeItem(menuItemId));
    }

    public void clear(String userId) {
        update(userId, Cart::clear);
    }

    public CheckoutCart loadForCheckout(String userId) {
        Cart cart = loadOrCreate(userId);
        return new CheckoutCart(cart, cartPricer.price(cart));
    }

    /**
     * Empties the cart after an order is placed. No retry here: if the cart changed since checkout read it,
     * the version check fails and the caller's transaction rolls back, so the order isn't created from a stale cart.
     */
    public void clearAfterCheckout(Cart cart) {
        cart.clear();
        cart.touch(clock.instant());
        cartRepository.save(cart);
    }

    /**
     * Read-modify-write with optimistic locking. If another request saved the cart after we read it
     * (version mismatch) or created it first (unique userId), re-read the latest cart and apply the
     * change again. The change is re-run from scratch, so business rules are checked against current data.
     */
    private CartResponse update(String userId, Consumer<Cart> change) {
        for (int attempt = 1; ; attempt++) {
            Cart cart = loadOrCreate(userId);
            change.accept(cart);
            cart.touch(clock.instant());
            try {
                return cartPricer.price(cartRepository.save(cart));
            } catch (OptimisticLockingFailureException | DuplicateKeyException e) {
                if (attempt == MAX_ATTEMPTS) {
                    log.warn("Cart update for user {} still conflicting after {} attempts", userId, attempt);
                    throw new BusinessException(ErrorCode.CONCURRENT_MODIFICATION,
                            "Your cart was changed by another request. Please try again.");
                }
                backOff(attempt);
            }
        }
    }

    private Cart loadOrCreate(String userId) {
        return cartRepository.findByUserId(userId).orElseGet(() -> Cart.emptyFor(userId));
    }

    // Random, growing pause so competing requests don't keep colliding in lockstep.
    // Cheap on Java 21 virtual threads: a sleeping virtual thread doesn't hold an OS thread.
    private static void backOff(int attempt) {
        try {
            Thread.sleep(ThreadLocalRandom.current().nextLong(5, 20) * attempt);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("Interrupted while retrying cart update", e);
        }
    }
}
