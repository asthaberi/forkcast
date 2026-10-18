package com.asthaberi.forkcast.auth;

import com.asthaberi.forkcast.auth.dto.LoginRequest;
import com.asthaberi.forkcast.auth.dto.RegisterRequest;
import com.asthaberi.forkcast.auth.dto.TokenResponse;
import com.asthaberi.forkcast.ratelimit.RateLimit;
import com.asthaberi.forkcast.user.dto.UserResponse;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/auth")
@RequiredArgsConstructor
public class AuthController {

    private final AuthService authService;

    @RateLimit(policy = "register", by = RateLimit.KeyType.IP)
    @PostMapping("/register")
    @ResponseStatus(HttpStatus.CREATED)
    public UserResponse register(@Valid @RequestBody RegisterRequest request) {
        return authService.register(request);
    }

    /** Returns a bearer token to send as {@code Authorization: Bearer <token>}. */
    @RateLimit(policy = "login", by = RateLimit.KeyType.IP)
    @PostMapping("/login")
    public TokenResponse login(@Valid @RequestBody LoginRequest request) {
        return authService.login(request);
    }
}
