package com.asthaberi.forkcast.menu;

/**
 * Published after a menu item is created, updated or deleted, so other features (like the search index)
 * can react without the menu module knowing about them.
 */
public record MenuItemChangedEvent(String menuItemId, boolean deleted) {
}
