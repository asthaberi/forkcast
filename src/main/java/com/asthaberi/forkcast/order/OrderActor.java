package com.asthaberi.forkcast.order;

/** Who changed an order's status. */
public enum OrderActor {
    CUSTOMER,
    /** The restaurant's owner, or an admin acting for the restaurant. */
    RESTAURANT
}
