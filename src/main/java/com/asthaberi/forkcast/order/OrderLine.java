package com.asthaberi.forkcast.order;

import java.math.BigDecimal;

/**
 * An ordered item with its price frozen at checkout. Unlike a cart line, an order line never
 * changes when the menu does: the customer pays what they saw.
 */
public record OrderLine(String menuItemId, String name, BigDecimal unitPrice, int quantity, BigDecimal lineTotal) {
}
