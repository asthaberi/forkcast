package com.asthaberi.forkcast.cart;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.List;
import java.util.Map;
import java.util.function.Function;
import java.util.stream.Collectors;

import com.asthaberi.forkcast.cart.dto.CartResponse;
import com.asthaberi.forkcast.cart.dto.CartResponse.CartLineResponse;
import com.asthaberi.forkcast.menu.MenuService;
import com.asthaberi.forkcast.menu.dto.MenuItemResponse;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

/**
 * Prices a cart against the restaurant's current menu. Reads the menu through MenuService,
 * so pricing is served from the Redis menu cache and adds no database load.
 */
@Component
@RequiredArgsConstructor
public class CartPricer {

    private static final int MONEY_SCALE = 2;

    private final MenuService menuService;
    private final PricingProperties pricing;

    public CartResponse price(Cart cart) {
        if (cart.isEmpty()) {
            return CartResponse.empty();
        }
        Map<String, MenuItemResponse> menu = menuService.getMenu(cart.getRestaurantId()).categories().stream()
                .flatMap(category -> category.items().stream())
                .collect(Collectors.toMap(MenuItemResponse::id, Function.identity()));

        List<CartLineResponse> lines = cart.getLines().stream()
                .map(line -> priceLine(line, menu.get(line.menuItemId())))
                .toList();

        BigDecimal subtotal = money(lines.stream()
                .map(CartLineResponse::lineTotal)
                .reduce(BigDecimal.ZERO, BigDecimal::add));
        BigDecimal deliveryFee = money(subtotal.signum() == 0 || subtotal.compareTo(pricing.freeDeliveryThreshold()) >= 0
                ? BigDecimal.ZERO
                : pricing.deliveryFee());
        BigDecimal tax = money(subtotal.multiply(pricing.taxRate()));
        int itemCount = lines.stream().filter(CartLineResponse::available).mapToInt(CartLineResponse::quantity).sum();

        return new CartResponse(cart.getRestaurantId(), cart.getRestaurantName(), lines, itemCount,
                subtotal, deliveryFee, tax, subtotal.add(deliveryFee).add(tax));
    }

    private static CartLineResponse priceLine(CartLine line, MenuItemResponse current) {
        if (current == null) {
            return new CartLineResponse(line.menuItemId(), line.name(), null, line.quantity(), money(BigDecimal.ZERO), false);
        }
        BigDecimal lineTotal = current.available()
                ? current.price().multiply(BigDecimal.valueOf(line.quantity()))
                : BigDecimal.ZERO;
        return new CartLineResponse(line.menuItemId(), current.name(), money(current.price()), line.quantity(),
                money(lineTotal), current.available());
    }

    // Round half-up to paise. BigDecimal keeps 0.1 + 0.2 exactly 0.3, unlike double.
    private static BigDecimal money(BigDecimal amount) {
        return amount.setScale(MONEY_SCALE, RoundingMode.HALF_UP);
    }
}
