package com.asthaberi.forkcast.order;

/**
 * Order lifecycle. The restaurant-side states (accepted, preparing, out for delivery, delivered)
 * are added with the order state machine in a later step.
 */
public enum OrderStatus {
    PLACED,
    CANCELLED
}
