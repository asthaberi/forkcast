package com.asthaberi.forkcast.security;

import com.asthaberi.forkcast.auth.TokenService;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.HttpMethod;
import org.springframework.security.config.annotation.method.configuration.EnableMethodSecurity;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.crypto.factory.PasswordEncoderFactories;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationConverter;
import org.springframework.security.oauth2.server.resource.authentication.JwtGrantedAuthoritiesConverter;
import org.springframework.security.web.SecurityFilterChain;

@Configuration
@EnableMethodSecurity
public class SecurityConfig {

    private static final String CUSTOMER = "CUSTOMER";
    private static final String OWNER = "RESTAURANT_OWNER";
    private static final String ADMIN = "ADMIN";

    /**
     * Stateless JWT API: no server-side session and no cookies. CSRF protection is disabled because CSRF
     * abuses cookies the browser attaches automatically; a bearer token has to be added by the client
     * itself, so another site can't forge an authenticated request.
     */
    @Bean
    SecurityFilterChain securityFilterChain(HttpSecurity http, ProblemDetailsSecurityHandlers handlers) throws Exception {
        http
                .csrf(csrf -> csrf.disable())
                .sessionManagement(session -> session.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
                .authorizeHttpRequests(auth -> auth
                        .requestMatchers("/api/v1/auth/**").permitAll()
                        .requestMatchers(HttpMethod.GET, "/api/v1/restaurants/**").permitAll()
                        .requestMatchers(HttpMethod.POST, "/api/v1/restaurants").hasAnyRole(OWNER, ADMIN)
                        // Which restaurant's menu an owner may edit is checked per request by RestaurantAccess
                        .requestMatchers("/api/v1/restaurants/*/menu/**").hasAnyRole(OWNER, ADMIN)
                        .requestMatchers("/api/v1/cart/**", "/api/v1/orders/**").hasRole(CUSTOMER)
                        .requestMatchers("/actuator/health/**", "/actuator/info").permitAll()
                        .requestMatchers("/actuator/**").hasRole(ADMIN)
                        // Spring Boot forwards errors to /error; without this, every error would turn into a 401
                        .requestMatchers("/error").permitAll()
                        // Anything not listed above needs at least a valid token (deny by default)
                        .anyRequest().authenticated())
                .oauth2ResourceServer(oauth2 -> oauth2
                        .jwt(jwt -> jwt.jwtAuthenticationConverter(jwtAuthenticationConverter()))
                        .authenticationEntryPoint(handlers)
                        .accessDeniedHandler(handlers))
                .exceptionHandling(exceptions -> exceptions
                        .authenticationEntryPoint(handlers)
                        .accessDeniedHandler(handlers));
        return http.build();
    }

    /** Turns the token's "roles" claim (["CUSTOMER"]) into Spring authorities (ROLE_CUSTOMER) for hasRole(). */
    @Bean
    JwtAuthenticationConverter jwtAuthenticationConverter() {
        JwtGrantedAuthoritiesConverter authorities = new JwtGrantedAuthoritiesConverter();
        authorities.setAuthoritiesClaimName(TokenService.ROLES_CLAIM);
        authorities.setAuthorityPrefix("ROLE_");
        JwtAuthenticationConverter converter = new JwtAuthenticationConverter();
        converter.setJwtGrantedAuthoritiesConverter(authorities);
        return converter;
    }

    /**
     * BCrypt today, stored as "{bcrypt}..." so the algorithm can be upgraded later
     * without breaking existing hashes.
     */
    @Bean
    PasswordEncoder passwordEncoder() {
        return PasswordEncoderFactories.createDelegatingPasswordEncoder();
    }
}
