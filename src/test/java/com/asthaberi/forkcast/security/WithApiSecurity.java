package com.asthaberi.forkcast.security;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

import com.asthaberi.forkcast.auth.JwtConfig;
import com.asthaberi.forkcast.auth.JwtProperties;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Import;

/**
 * For @WebMvcTest slices: loads the application's real security rules (which a slice doesn't pick up on
 * its own), so controller tests check the same 401/403 behaviour as production.
 */
@Target(ElementType.TYPE)
@Retention(RetentionPolicy.RUNTIME)
@Import({SecurityConfig.class, ProblemDetailsSecurityHandlers.class, JwtConfig.class})
@EnableConfigurationProperties(JwtProperties.class)
public @interface WithApiSecurity {
}
