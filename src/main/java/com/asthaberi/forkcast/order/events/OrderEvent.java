package com.asthaberi.forkcast.order.events;

import java.time.Instant;

import com.asthaberi.forkcast.order.Order;
import com.asthaberi.forkcast.order.OrderStatus;

/**
 * Pushed to live-tracking streams whenever an order is placed or changes status. It goes both to the
 * customer and to the restaurant, so it carries no personal data.
 *
 * @param sequence position in the order's status history; becomes the SSE event id
 */
public record OrderEvent(String orderId, String restaurantId, OrderStatus status, Instant at, int sequence) {

    public static OrderEvent of(Order order) {
        return new OrderEvent(order.getId(), order.getRestaurantId(), order.getStatus(), order.lastChangedAt(),
                order.getStatusHistory().size());
    }
}
