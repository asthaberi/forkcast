package com.asthaberi.forkcast.auth;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Optional;
import java.util.Set;

import com.asthaberi.forkcast.auth.dto.LoginRequest;
import com.asthaberi.forkcast.auth.dto.RegisterRequest;
import com.asthaberi.forkcast.auth.dto.RegisterRequest.AccountType;
import com.asthaberi.forkcast.auth.dto.TokenResponse;
import com.asthaberi.forkcast.common.exception.BusinessException;
import com.asthaberi.forkcast.common.exception.ErrorCode;
import com.asthaberi.forkcast.ratelimit.RateLimitDecision;
import com.asthaberi.forkcast.ratelimit.RateLimitExceededException;
import com.asthaberi.forkcast.ratelimit.RateLimiter;
import com.asthaberi.forkcast.user.Role;
import com.asthaberi.forkcast.user.User;
import com.asthaberi.forkcast.user.UserRepository;
import com.asthaberi.forkcast.user.dto.UserResponse;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.security.crypto.factory.PasswordEncoderFactories;
import org.springframework.security.crypto.password.PasswordEncoder;

@ExtendWith(MockitoExtension.class)
class AuthServiceTest {

    private final PasswordEncoder passwordEncoder = PasswordEncoderFactories.createDelegatingPasswordEncoder();

    @Mock
    private UserRepository userRepository;
    @Mock
    private TokenService tokenService;

    // Allows everything unless a test says otherwise
    private RateLimiter rateLimiter = RateLimiter.DISABLED;

    private AuthService authService;

    @BeforeEach
    void setUp() {
        authService = new AuthService(userRepository, passwordEncoder, tokenService,
                Clock.fixed(Instant.parse("2026-10-18T07:00:00Z"), ZoneOffset.UTC), rateLimiter);
    }

    @Test
    void registersCustomerWithNormalizedEmailAndHashedPassword() {
        when(userRepository.save(any())).thenAnswer(invocation -> invocation.getArgument(0));

        UserResponse response = authService.register(
                new RegisterRequest(" Astha ", "  Astha@Example.COM ", "correct-horse-battery", null));

        ArgumentCaptor<User> saved = ArgumentCaptor.forClass(User.class);
        verify(userRepository).save(saved.capture());
        assertThat(saved.getValue().getEmail()).isEqualTo("astha@example.com");
        assertThat(saved.getValue().getPasswordHash()).startsWith("{bcrypt}").doesNotContain("correct-horse");
        assertThat(response.roles()).containsExactly("CUSTOMER");
    }

    @Test
    void canRegisterAsRestaurantOwner() {
        when(userRepository.save(any())).thenAnswer(invocation -> invocation.getArgument(0));

        UserResponse response = authService.register(
                new RegisterRequest("Owner", "owner@example.com", "long-enough-password", AccountType.RESTAURANT_OWNER));

        assertThat(response.roles()).containsExactly("RESTAURANT_OWNER");
    }

    @Test
    void rejectsAlreadyRegisteredEmailIncludingConcurrentSignUps() {
        when(userRepository.findByEmail("taken@example.com"))
                .thenReturn(Optional.of(user("taken@example.com", "whatever-password")))
                .thenReturn(Optional.empty());
        when(userRepository.save(any())).thenThrow(new DuplicateKeyException("email"));
        RegisterRequest request = new RegisterRequest("X", "taken@example.com", "long-enough-password", null);

        assertCode(() -> authService.register(request), ErrorCode.EMAIL_ALREADY_REGISTERED);
        // Second time the pre-check passes, but the unique index catches it
        assertCode(() -> authService.register(request), ErrorCode.EMAIL_ALREADY_REGISTERED);
    }

    @Test
    void rejectsPasswordsLongerThanBcryptsLimitInBytes() {
        // 30 characters but 90 bytes in UTF-8
        String password = "€".repeat(30);

        assertCode(() -> authService.register(new RegisterRequest("X", "x@example.com", password, null)),
                ErrorCode.PASSWORD_TOO_LONG);
        verify(userRepository, never()).save(any());
    }

    @Test
    void loginWithCorrectPasswordIssuesToken() {
        User user = user("astha@example.com", "correct-horse-battery");
        when(userRepository.findByEmail("astha@example.com")).thenReturn(Optional.of(user));
        when(tokenService.issueAccessToken(user)).thenReturn(new TokenResponse("token", "Bearer", 3600));

        assertThat(authService.login(new LoginRequest("ASTHA@example.com", "correct-horse-battery")).accessToken())
                .isEqualTo("token");
    }

    @Test
    void wrongPasswordAndUnknownEmailFailIdentically() {
        when(userRepository.findByEmail("astha@example.com"))
                .thenReturn(Optional.of(user("astha@example.com", "correct-horse-battery")));
        when(userRepository.findByEmail("nobody@example.com")).thenReturn(Optional.empty());

        BusinessException wrongPassword = catchBusiness(() -> authService.login(new LoginRequest("astha@example.com", "nope-nope")));
        BusinessException unknownEmail = catchBusiness(() -> authService.login(new LoginRequest("nobody@example.com", "nope-nope")));

        assertThat(wrongPassword.code()).isEqualTo(ErrorCode.INVALID_CREDENTIALS);
        assertThat(unknownEmail.code()).isEqualTo(ErrorCode.INVALID_CREDENTIALS);
        assertThat(unknownEmail.getMessage()).isEqualTo(wrongPassword.getMessage());
        verify(tokenService, never()).issueAccessToken(any());
    }

    @Test
    void perAccountLoginLimitRejectsBeforeCheckingThePassword() {
        RateLimitDecision exhausted = new RateLimitDecision(false, 10, 0, Duration.ofSeconds(90));
        rateLimiter = (policy, key) -> {
            assertThat(policy).isEqualTo("login-account");
            assertThat(key).isEqualTo("astha@example.com");
            return exhausted;
        };
        setUp();

        assertThatThrownBy(() -> authService.login(new LoginRequest(" Astha@Example.com", "guess")))
                .isInstanceOf(RateLimitExceededException.class)
                .hasMessageContaining("90 s");
        verify(userRepository, never()).findByEmail(any());
    }

    private User user(String email, String password) {
        return User.create("Test", email, passwordEncoder.encode(password), Set.of(Role.CUSTOMER), Instant.now());
    }

    private static BusinessException catchBusiness(Runnable call) {
        try {
            call.run();
        } catch (BusinessException e) {
            return e;
        }
        throw new AssertionError("expected BusinessException");
    }

    private static void assertCode(org.assertj.core.api.ThrowableAssert.ThrowingCallable call, ErrorCode code) {
        assertThatThrownBy(call).isInstanceOfSatisfying(BusinessException.class,
                ex -> assertThat(ex.code()).isEqualTo(code));
    }
}
