package com.asthaberi.forkcast.order.dto;

import java.math.BigDecimal;
import java.time.Instant;

import com.asthaberi.forkcast.order.OrderStatus;

/** One row in the order history list. */
public record OrderSummary(String id, OrderStatus status, String restaurantName, int itemCount, BigDecimal total,
                           Instant placedAt) {
}
