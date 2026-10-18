package com.asthaberi.forkcast.cart.dto;

import java.math.BigDecimal;
import java.util.List;

/**
 * A priced cart. Amounts are in rupees with 2 decimal places.
 *
 * @param itemCount total quantity of items that can be ordered (unavailable lines excluded)
 */
public record CartResponse(
        String restaurantId,
        String restaurantName,
        List<CartLineResponse> items,
        int itemCount,
        BigDecimal subtotal,
        BigDecimal deliveryFee,
        BigDecimal tax,
        BigDecimal total) {

    private static final BigDecimal ZERO = new BigDecimal("0.00");

    public static CartResponse empty() {
        return new CartResponse(null, null, List.of(), 0, ZERO, ZERO, ZERO, ZERO);
    }

    /**
     * @param unitPrice current menu price, or {@code null} if the dish was removed from the menu
     * @param available false if the dish is sold out or removed; such lines aren't charged
     */
    public record CartLineResponse(String menuItemId, String name, BigDecimal unitPrice, int quantity,
                                   BigDecimal lineTotal, boolean available) {
    }
}
