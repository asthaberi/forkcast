package com.asthaberi.forkcast.order.dto;

import com.asthaberi.forkcast.order.DeliveryAddress;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotNull;

/** Items and prices come from the user's cart on the server, never from the request. */
public record PlaceOrderRequest(@NotNull @Valid DeliveryAddress deliveryAddress) {
}
