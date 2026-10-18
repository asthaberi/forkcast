package com.asthaberi.forkcast.user;

public enum Role {
    /** Browses restaurants, uses a cart and places orders. */
    CUSTOMER,
    /** Manages the restaurants they own and their menus. */
    RESTAURANT_OWNER,
    /** Manages everything. Never self-assignable at sign-up. */
    ADMIN
}
