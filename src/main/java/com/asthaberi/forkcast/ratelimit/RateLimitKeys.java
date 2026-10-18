package com.asthaberi.forkcast.ratelimit;

import jakarta.servlet.http.HttpServletRequest;
import org.springframework.security.authentication.AnonymousAuthenticationToken;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;

final class RateLimitKeys {

    private RateLimitKeys() {
    }

    /**
     * The address the TCP connection came from. Deliberately not X-Forwarded-For, which any client can set
     * to dodge per-IP limits. Behind a load balancer, set server.forward-headers-strategy=native: Tomcat
     * then takes the client IP from that header only when the request came from a trusted proxy address.
     */
    static String ip(HttpServletRequest request) {
        return "ip:" + request.getRemoteAddr();
    }

    /** The signed-in user's id, or the client IP for anonymous requests. */
    static String userOrIp(HttpServletRequest request) {
        Authentication authentication = SecurityContextHolder.getContext().getAuthentication();
        if (authentication != null && authentication.isAuthenticated()
                && !(authentication instanceof AnonymousAuthenticationToken)) {
            return "user:" + authentication.getName();
        }
        return ip(request);
    }
}
