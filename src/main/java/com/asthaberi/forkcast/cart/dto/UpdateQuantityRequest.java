package com.asthaberi.forkcast.cart.dto;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;

import com.asthaberi.forkcast.cart.Cart;

/** Quantity 0 removes the item. */
public record UpdateQuantityRequest(@Min(0) @Max(Cart.MAX_QUANTITY_PER_ITEM) int quantity) {
}
