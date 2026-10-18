package com.asthaberi.forkcast.security;

import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;

import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.JwtRequestPostProcessor;

/** MockMvc request post-processors that act as an already-authenticated user with a given role. */
public final class TestJwt {

    private TestJwt() {
    }

    public static JwtRequestPostProcessor customer(String userId) {
        return as(userId, "CUSTOMER");
    }

    public static JwtRequestPostProcessor owner(String userId) {
        return as(userId, "RESTAURANT_OWNER");
    }

    public static JwtRequestPostProcessor as(String userId, String role) {
        return jwt().jwt(token -> token.subject(userId)).authorities(new SimpleGrantedAuthority("ROLE_" + role));
    }
}
