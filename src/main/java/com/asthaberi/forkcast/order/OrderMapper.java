package com.asthaberi.forkcast.order;

import java.util.List;

import com.asthaberi.forkcast.cart.dto.CartResponse;
import com.asthaberi.forkcast.order.dto.OrderResponse;
import com.asthaberi.forkcast.order.dto.OrderSummary;

final class OrderMapper {

    private OrderMapper() {
    }

    static List<OrderLine> toLines(CartResponse cart) {
        return cart.items().stream()
                .map(line -> new OrderLine(line.menuItemId(), line.name(), line.unitPrice(), line.quantity(),
                        line.lineTotal()))
                .toList();
    }

    static OrderResponse toResponse(Order order) {
        return new OrderResponse(order.getId(), order.getStatus(), order.getRestaurantId(), order.getRestaurantName(),
                order.getLines(), order.itemCount(), order.getSubtotal(), order.getDeliveryFee(), order.getTax(),
                order.getTotal(), order.getDeliveryAddress(), order.getPlacedAt(), List.copyOf(order.getStatusHistory()));
    }

    static OrderSummary toSummary(Order order) {
        return new OrderSummary(order.getId(), order.getStatus(), order.getRestaurantName(), order.itemCount(),
                order.getTotal(), order.getPlacedAt());
    }
}
