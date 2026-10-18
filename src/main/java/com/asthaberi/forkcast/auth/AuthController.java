package com.asthaberi.forkcast.auth;

import com.asthaberi.forkcast.auth.dto.LoginRequest;
import com.asthaberi.forkcast.auth.dto.RegisterRequest;
import com.asthaberi.forkcast.auth.dto.TokenResponse;
import com.asthaberi.forkcast.ratelimit.RateLimit;
import com.asthaberi.forkcast.user.dto.UserResponse;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.security.SecurityRequirements;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

@RestController
@Tag(name = "Authentication", description = "Sign up and log in to get a bearer token")
@RequestMapping("/api/v1/auth")
@RequiredArgsConstructor
public class AuthController {

    private final AuthService authService;

    @RateLimit(policy = "register", by = RateLimit.KeyType.IP)
    @PostMapping("/register")
    @ResponseStatus(HttpStatus.CREATED)
    @Operation(summary = "Create an account (customer or restaurant owner)")
    @SecurityRequirements // public: no token needed
    @ApiResponse(responseCode = "201", description = "Account created")
    @ApiResponse(responseCode = "409", description = "EMAIL_ALREADY_REGISTERED")
    @ApiResponse(responseCode = "422", description = "PASSWORD_TOO_LONG: over 72 bytes")
    public UserResponse register(@Valid @RequestBody RegisterRequest request) {
        return authService.register(request);
    }

    /** Returns a bearer token to send as {@code Authorization: Bearer <token>}. */
    @RateLimit(policy = "login", by = RateLimit.KeyType.IP)
    @PostMapping("/login")
    @Operation(summary = "Log in and receive a JWT access token")
    @SecurityRequirements // public: no token needed
    @ApiResponse(responseCode = "401",
            description = "INVALID_CREDENTIALS: same answer for unknown email and wrong password")
    public TokenResponse login(@Valid @RequestBody LoginRequest request) {
        return authService.login(request);
    }
}
