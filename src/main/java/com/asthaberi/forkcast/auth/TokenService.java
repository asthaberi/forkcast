package com.asthaberi.forkcast.auth;

import java.time.Clock;
import java.time.Instant;

import com.asthaberi.forkcast.auth.dto.TokenResponse;
import com.asthaberi.forkcast.user.Role;
import com.asthaberi.forkcast.user.User;
import lombok.RequiredArgsConstructor;
import org.springframework.security.oauth2.jose.jws.MacAlgorithm;
import org.springframework.security.oauth2.jwt.JwsHeader;
import org.springframework.security.oauth2.jwt.JwtClaimsSet;
import org.springframework.security.oauth2.jwt.JwtEncoder;
import org.springframework.security.oauth2.jwt.JwtEncoderParameters;
import org.springframework.stereotype.Service;

@Service
@RequiredArgsConstructor
public class TokenService {

    public static final String ROLES_CLAIM = "roles";

    private final JwtEncoder jwtEncoder;
    private final JwtProperties properties;
    private final Clock clock;

    /**
     * The token carries the user id (subject) and roles, so authorizing a request needs no database lookup.
     * The flip side: a role change or ban only takes effect when the token expires, hence the short lifetime.
     */
    public TokenResponse issueAccessToken(User user) {
        Instant now = clock.instant();
        JwtClaimsSet claims = JwtClaimsSet.builder()
                .issuer(properties.issuer())
                .subject(user.getId())
                .issuedAt(now)
                .expiresAt(now.plus(properties.accessTokenTtl()))
                .claim(ROLES_CLAIM, user.getRoles().stream().map(Role::name).sorted().toList())
                .build();
        JwsHeader header = JwsHeader.with(MacAlgorithm.HS256).type("JWT").build();
        String token = jwtEncoder.encode(JwtEncoderParameters.from(header, claims)).getTokenValue();
        return new TokenResponse(token, "Bearer", properties.accessTokenTtl().toSeconds());
    }
}
