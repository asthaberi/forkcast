package com.asthaberi.forkcast.cart;

/**
 * One item in a cart. Deliberately stores no price: prices are looked up from the current
 * menu every time the cart is priced, so a stale or tampered price can never be charged.
 * The name is kept only to label the line if the dish is later removed from the menu.
 */
public record CartLine(String menuItemId, String name, int quantity) {

    CartLine withQuantity(int newQuantity) {
        return new CartLine(menuItemId, name, newQuantity);
    }
}
