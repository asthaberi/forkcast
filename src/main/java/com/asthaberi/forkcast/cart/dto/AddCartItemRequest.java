package com.asthaberi.forkcast.cart.dto;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;

import com.asthaberi.forkcast.cart.Cart;

public record AddCartItemRequest(
        @NotBlank String menuItemId,
        @Min(1) @Max(Cart.MAX_QUANTITY_PER_ITEM) int quantity) {
}
