package com.asthaberi.forkcast.auth;

import java.time.Duration;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Bound from {@code forkcast.security.jwt.*}.
 *
 * @param secret         HMAC signing key, at least 32 bytes; must come from the JWT_SECRET env var outside local dev
 * @param issuer         written to and required in every token's "iss" claim
 * @param accessTokenTtl how long a token stays valid; short, because a stateless token can't be revoked early
 */
@ConfigurationProperties(prefix = "forkcast.security.jwt")
public record JwtProperties(String secret, String issuer, Duration accessTokenTtl) {
}
