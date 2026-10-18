package com.asthaberi.forkcast.cart;

import java.math.BigDecimal;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Order charges, bound from {@code forkcast.pricing.*}.
 *
 * @param freeDeliveryThreshold subtotal at or above which delivery is free
 * @param taxRate               e.g. 0.05 for 5% GST, applied to the food subtotal
 */
@ConfigurationProperties(prefix = "forkcast.pricing")
public record PricingProperties(BigDecimal deliveryFee, BigDecimal freeDeliveryThreshold, BigDecimal taxRate) {
}
