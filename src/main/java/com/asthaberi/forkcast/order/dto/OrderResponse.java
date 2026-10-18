package com.asthaberi.forkcast.order.dto;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;

import com.asthaberi.forkcast.order.DeliveryAddress;
import com.asthaberi.forkcast.order.Order.StatusChange;
import com.asthaberi.forkcast.order.OrderLine;
import com.asthaberi.forkcast.order.OrderStatus;

public record OrderResponse(
        String id,
        OrderStatus status,
        String restaurantId,
        String restaurantName,
        List<OrderLine> items,
        int itemCount,
        BigDecimal subtotal,
        BigDecimal deliveryFee,
        BigDecimal tax,
        BigDecimal total,
        DeliveryAddress deliveryAddress,
        Instant placedAt,
        List<StatusChange> statusHistory) {
}
