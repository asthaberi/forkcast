package com.asthaberi.forkcast.security;

import com.asthaberi.forkcast.auth.TokenService;
import com.asthaberi.forkcast.ratelimit.ApiRateLimitFilter;
import com.asthaberi.forkcast.ratelimit.RateLimiter;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.env.Environment;
import org.springframework.http.HttpMethod;
import org.springframework.security.config.annotation.method.configuration.EnableMethodSecurity;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.crypto.factory.PasswordEncoderFactories;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationConverter;
import org.springframework.security.oauth2.server.resource.authentication.JwtGrantedAuthoritiesConverter;
import org.springframework.security.oauth2.server.resource.web.authentication.BearerTokenAuthenticationFilter;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.servlet.util.matcher.PathPatternRequestMatcher;
import org.springframework.security.web.util.matcher.AndRequestMatcher;
import org.springframework.security.web.util.matcher.RequestMatcher;
import tools.jackson.databind.json.JsonMapper;

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
    SecurityFilterChain securityFilterChain(HttpSecurity http, ProblemDetailsSecurityHandlers handlers,
                                            ObjectProvider<RateLimiter> rateLimiter, JsonMapper jsonMapper,
                                            Environment environment)
            throws Exception {
        http
                .csrf(csrf -> csrf.disable())
                .sessionManagement(session -> session.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
                .authorizeHttpRequests(auth -> auth
                        .requestMatchers("/api/v1/auth/**").permitAll()
                        .requestMatchers(HttpMethod.GET, "/api/v1/search/**").permitAll()
                        .requestMatchers("/api/v1/admin/**").hasRole(ADMIN)
                        // Must come before the public GET rule below: a restaurant's orders aren't public
                        .requestMatchers("/api/v1/restaurants/*/orders/**").hasAnyRole(OWNER, ADMIN)
                        .requestMatchers(HttpMethod.GET, "/api/v1/restaurants/**").permitAll()
                        .requestMatchers(HttpMethod.POST, "/api/v1/restaurants").hasAnyRole(OWNER, ADMIN)
                        // Which restaurant's menu or orders an owner may touch is checked per request by RestaurantAccess
                        .requestMatchers("/api/v1/restaurants/*/menu/**").hasAnyRole(OWNER, ADMIN)
                        .requestMatchers("/api/v1/cart/**", "/api/v1/orders/**", "/api/v1/recommendations/**").hasRole(CUSTOMER)
                        .requestMatchers("/actuator/health/**", "/actuator/info", "/livez", "/readyz").permitAll()
                        // Prometheus scrapes without a token, but only on the internal management port
                        .requestMatchers(new AndRequestMatcher(
                                PathPatternRequestMatcher.withDefaults().matcher("/actuator/prometheus"),
                                onManagementPort(environment))).permitAll()
                        // API docs (turned off in production with API_DOCS_ENABLED=false)
                        .requestMatchers("/v3/api-docs/**", "/swagger-ui/**", "/swagger-ui.html").permitAll()
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
        // Created here rather than as a bean: a Filter bean would also be registered by Spring Boot as a
        // servlet filter and run twice per request. Absent in web-slice tests, which have no RateLimiter.
        rateLimiter.ifAvailable(limiter -> http.addFilterAfter(new ApiRateLimitFilter(limiter, jsonMapper),
                BearerTokenAuthenticationFilter.class));
        return http.build();
    }

    /**
     * Requests that arrived on the separate management port (management.server.port), which is internal and
     * never exposed publicly. If management shares the API port, this matches nothing, so the Prometheus
     * endpoint falls back to admin-only instead of becoming public.
     */
    private static RequestMatcher onManagementPort(Environment environment) {
        return request -> {
            Integer managementPort = environment.getProperty("local.management.port", Integer.class);
            Integer apiPort = environment.getProperty("local.server.port", Integer.class);
            return managementPort != null && !managementPort.equals(apiPort)
                    && request.getLocalPort() == managementPort;
        };
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
