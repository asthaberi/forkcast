package com.asthaberi.forkcast.order.dto;

import com.asthaberi.forkcast.order.OrderStatus;
import jakarta.validation.constraints.NotNull;

public record UpdateOrderStatusRequest(@NotNull OrderStatus status) {
}
