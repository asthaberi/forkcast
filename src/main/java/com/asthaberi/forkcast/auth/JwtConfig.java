package com.asthaberi.forkcast.auth;

import java.nio.charset.StandardCharsets;

import javax.crypto.SecretKey;
import javax.crypto.spec.SecretKeySpec;

import lombok.extern.slf4j.Slf4j;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.security.oauth2.jose.jws.MacAlgorithm;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.security.oauth2.jwt.JwtEncoder;
import org.springframework.security.oauth2.jwt.JwtValidators;
import org.springframework.security.oauth2.jwt.NimbusJwtDecoder;
import org.springframework.security.oauth2.jwt.NimbusJwtEncoder;

/**
 * Tokens are signed with HMAC-SHA256 using one shared secret: this service both issues and verifies them.
 * If other services ever need to verify tokens, switch to an RSA key pair so they only get the public key.
 */
@Slf4j
@Configuration
public class JwtConfig {

    private static final int MIN_SECRET_BYTES = 32; // HS256 needs a 256-bit key

    @Bean
    SecretKey jwtSigningKey(JwtProperties properties) {
        byte[] secret = properties.secret().getBytes(StandardCharsets.UTF_8);
        if (secret.length < MIN_SECRET_BYTES) {
            throw new IllegalStateException("forkcast.security.jwt.secret must be at least " + MIN_SECRET_BYTES + " bytes");
        }
        if (properties.secret().startsWith("dev-only")) {
            log.warn("Using the development JWT secret. Set JWT_SECRET before deploying anywhere.");
        }
        return new SecretKeySpec(secret, "HmacSHA256");
    }

    @Bean
    JwtEncoder jwtEncoder(SecretKey jwtSigningKey) {
        return NimbusJwtEncoder.withSecretKey(jwtSigningKey).algorithm(MacAlgorithm.HS256).build();
    }

    /**
     * Used by Spring Security's resource server on every request: checks the signature, then the
     * expiry/not-before times (60 s clock-skew allowance) and that the issuer is ours.
     */
    @Bean
    JwtDecoder jwtDecoder(SecretKey jwtSigningKey, JwtProperties properties) {
        NimbusJwtDecoder decoder = NimbusJwtDecoder.withSecretKey(jwtSigningKey).macAlgorithm(MacAlgorithm.HS256).build();
        decoder.setJwtValidator(JwtValidators.createDefaultWithIssuer(properties.issuer()));
        return decoder;
    }
}
