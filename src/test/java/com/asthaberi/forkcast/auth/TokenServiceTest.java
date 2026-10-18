package com.asthaberi.forkcast.auth;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Set;

import javax.crypto.SecretKey;

import com.asthaberi.forkcast.auth.dto.TokenResponse;
import com.asthaberi.forkcast.user.Role;
import com.asthaberi.forkcast.user.User;
import org.junit.jupiter.api.Test;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.security.oauth2.jwt.JwtException;
import org.springframework.test.util.ReflectionTestUtils;

/** Issues tokens with the real JwtConfig beans and checks what the decoder accepts and rejects. */
class TokenServiceTest {

    private static final JwtProperties PROPERTIES =
            new JwtProperties("test-secret-that-is-definitely-32-bytes-long", "forkcast", Duration.ofMinutes(60));

    private final JwtConfig config = new JwtConfig();
    private final SecretKey key = config.jwtSigningKey(PROPERTIES);
    private final JwtDecoder decoder = config.jwtDecoder(key, PROPERTIES);

    @Test
    void tokenCarriesUserIdRolesIssuerAndExpiry() {
        TokenResponse token = tokenServiceAt(Instant.now()).issueAccessToken(user());

        Jwt jwt = decoder.decode(token.accessToken());
        assertThat(jwt.getSubject()).isEqualTo("user-1");
        assertThat(jwt.getClaimAsStringList("roles")).containsExactly("CUSTOMER");
        assertThat(jwt.getClaimAsString("iss")).isEqualTo("forkcast");
        assertThat(jwt.getExpiresAt()).isAfter(Instant.now().plus(Duration.ofMinutes(59)));
        assertThat(token.expiresIn()).isEqualTo(3600);
        // No personal data in the payload, which is only base64-encoded and readable by anyone
        assertThat(jwt.getClaims()).doesNotContainKeys("email", "name");
    }

    @Test
    void rejectsExpiredToken() {
        String expired = tokenServiceAt(Instant.now().minus(Duration.ofHours(2))).issueAccessToken(user()).accessToken();

        assertThatThrownBy(() -> decoder.decode(expired)).isInstanceOf(JwtException.class).hasMessageContaining("expired");
    }

    @Test
    void rejectsTamperedToken() {
        String token = tokenServiceAt(Instant.now()).issueAccessToken(user()).accessToken();
        String[] parts = token.split("\\.");
        // Swap the payload for one claiming the ADMIN role, keeping the original signature
        String forgedPayload = java.util.Base64.getUrlEncoder().withoutPadding().encodeToString(
                ("{\"sub\":\"user-1\",\"roles\":[\"ADMIN\"],\"iss\":\"forkcast\",\"exp\":" + (Instant.now().getEpochSecond() + 3600) + "}").getBytes());
        String forged = parts[0] + "." + forgedPayload + "." + parts[2];

        assertThatThrownBy(() -> decoder.decode(forged)).isInstanceOf(JwtException.class);
    }

    @Test
    void rejectsTokenSignedWithAnotherKey() {
        JwtProperties otherSecret = new JwtProperties("another-secret-also-at-least-32-bytes!!", "forkcast", Duration.ofMinutes(60));
        TokenService otherIssuer = new TokenService(config.jwtEncoder(config.jwtSigningKey(otherSecret)), otherSecret,
                Clock.systemUTC());

        String token = otherIssuer.issueAccessToken(user()).accessToken();

        assertThatThrownBy(() -> decoder.decode(token)).isInstanceOf(JwtException.class);
    }

    @Test
    void refusesShortSecrets() {
        assertThatThrownBy(() -> config.jwtSigningKey(new JwtProperties("too-short", "forkcast", Duration.ofMinutes(5))))
                .isInstanceOf(IllegalStateException.class);
    }

    private TokenService tokenServiceAt(Instant now) {
        return new TokenService(config.jwtEncoder(key), PROPERTIES, Clock.fixed(now, ZoneOffset.UTC));
    }

    private static User user() {
        User user = User.create("Test", "test@example.com", "{bcrypt}x", Set.of(Role.CUSTOMER), Instant.now());
        ReflectionTestUtils.setField(user, "id", "user-1");
        return user;
    }
}
