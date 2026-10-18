package com.asthaberi.forkcast.security;

import com.asthaberi.forkcast.restaurant.RestaurantRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Component;

/**
 * Object-level authorization: a role says what kind of thing a user may do ("edit menus"), this decides
 * which ones ("only menus of restaurants they own"). Used from @PreAuthorize as {@link #CAN_MANAGE}.
 */
@Component("restaurantAccess")
@RequiredArgsConstructor
public class RestaurantAccess {

    /** SpEL for controller methods with a {@code restaurantId} parameter. */
    public static final String CAN_MANAGE = "@restaurantAccess.canManage(#restaurantId)";

    private final RestaurantRepository restaurantRepository;

    /**
     * Admins can manage any restaurant; owners only their own. An unknown restaurant id returns false
     * for owners (403), so they can't probe which ids exist.
     */
    public boolean canManage(String restaurantId) {
        Authentication authentication = SecurityContextHolder.getContext().getAuthentication();
        if (authentication == null) {
            return false;
        }
        if (hasRole(authentication, "ADMIN")) {
            return true;
        }
        return hasRole(authentication, "RESTAURANT_OWNER")
                && restaurantRepository.findById(restaurantId)
                        .map(restaurant -> authentication.getName().equals(restaurant.getOwnerId()))
                        .orElse(false);
    }

    private static boolean hasRole(Authentication authentication, String role) {
        return authentication.getAuthorities().stream()
                .anyMatch(authority -> ("ROLE_" + role).equals(authority.getAuthority()));
    }
}
