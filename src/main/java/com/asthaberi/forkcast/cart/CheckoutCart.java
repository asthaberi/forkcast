package com.asthaberi.forkcast.cart;

import com.asthaberi.forkcast.cart.dto.CartResponse;

/**
 * A cart as read for checkout: the document (with its version, so clearing it afterwards fails if it
 * changed in the meantime) and its current pricing.
 */
public record CheckoutCart(Cart cart, CartResponse priced) {
}
