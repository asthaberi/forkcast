package com.asthaberi.forkcast.common.exception;

import org.springframework.http.HttpStatus;

/**
 * Machine-readable reasons a request broke a business rule. Clients switch on the code
 * (e.g. show a "replace your cart?" dialog) instead of parsing the message.
 */
public enum ErrorCode {

    CART_RESTAURANT_MISMATCH(HttpStatus.CONFLICT, "Cart contains items from another restaurant"),
    ITEM_UNAVAILABLE(HttpStatus.CONFLICT, "Item unavailable"),
    QUANTITY_LIMIT_EXCEEDED(HttpStatus.UNPROCESSABLE_CONTENT, "Quantity limit exceeded"),
    CONCURRENT_MODIFICATION(HttpStatus.CONFLICT, "Concurrent modification");

    private final HttpStatus status;
    private final String title;

    ErrorCode(HttpStatus status, String title) {
        this.status = status;
        this.title = title;
    }

    public HttpStatus status() {
        return status;
    }

    public String title() {
        return title;
    }
}
