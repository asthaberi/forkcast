package com.asthaberi.forkcast.auth;

import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

import com.asthaberi.forkcast.auth.dto.LoginRequest;
import com.asthaberi.forkcast.auth.dto.RegisterRequest;
import com.asthaberi.forkcast.auth.dto.RegisterRequest.AccountType;
import com.asthaberi.forkcast.auth.dto.TokenResponse;
import com.asthaberi.forkcast.common.exception.BusinessException;
import com.asthaberi.forkcast.common.exception.ErrorCode;
import com.asthaberi.forkcast.ratelimit.RateLimiter;
import com.asthaberi.forkcast.user.Role;
import com.asthaberi.forkcast.user.User;
import com.asthaberi.forkcast.user.UserRepository;
import com.asthaberi.forkcast.user.UserService;
import com.asthaberi.forkcast.user.dto.UserResponse;
import lombok.extern.slf4j.Slf4j;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;

@Slf4j
@Service
public class AuthService {

    // BCrypt silently ignores everything after 72 bytes, so longer passwords are rejected rather than truncated.
    static final int MAX_PASSWORD_BYTES = 72;

    private final UserRepository userRepository;
    private final PasswordEncoder passwordEncoder;
    private final TokenService tokenService;
    private final Clock clock;
    private final RateLimiter rateLimiter;
    private final String dummyPasswordHash;

    public AuthService(UserRepository userRepository, PasswordEncoder passwordEncoder, TokenService tokenService,
                       Clock clock, RateLimiter rateLimiter) {
        this.userRepository = userRepository;
        this.passwordEncoder = passwordEncoder;
        this.tokenService = tokenService;
        this.clock = clock;
        this.rateLimiter = rateLimiter;
        // Hash compared against when the email doesn't exist; see login()
        this.dummyPasswordHash = passwordEncoder.encode("forkcast-timing-equalizer");
    }

    public UserResponse register(RegisterRequest request) {
        if (request.password().getBytes(StandardCharsets.UTF_8).length > MAX_PASSWORD_BYTES) {
            throw new BusinessException(ErrorCode.PASSWORD_TOO_LONG,
                    "Password must be at most " + MAX_PASSWORD_BYTES + " bytes",
                    Map.of("maxBytes", MAX_PASSWORD_BYTES));
        }
        String email = normalizeEmail(request.email());
        if (userRepository.findByEmail(email).isPresent()) {
            throw emailTaken();
        }
        Role role = request.accountType() == AccountType.RESTAURANT_OWNER ? Role.RESTAURANT_OWNER : Role.CUSTOMER;
        User user = User.create(request.name().trim(), email, passwordEncoder.encode(request.password()),
                Set.of(role), clock.instant());
        try {
            User saved = userRepository.save(user);
            log.info("Registered user {} as {}", saved.getId(), role);
            return UserService.toResponse(saved);
        } catch (DuplicateKeyException e) {
            // Two sign-ups with the same email at the same moment: the unique index lets only one through
            throw emailTaken();
        }
    }

    /**
     * Unknown email and wrong password get the same error, and both run one BCrypt comparison, so neither
     * the message nor the response time tells an attacker which emails have accounts.
     */
    public TokenResponse login(LoginRequest request) {
        String email = normalizeEmail(request.email());
        // Per-account limit, on top of the per-IP one: slows password guessing against one account from
        // many IPs. Applied to unknown emails too, so the limit itself doesn't reveal which accounts exist.
        rateLimiter.consumeOrThrow("login-account", email);
        Optional<User> user = userRepository.findByEmail(email);
        boolean passwordMatches = request.password().getBytes(StandardCharsets.UTF_8).length <= MAX_PASSWORD_BYTES
                && passwordEncoder.matches(request.password(), user.map(User::getPasswordHash).orElse(dummyPasswordHash));
        if (user.isEmpty() || !passwordMatches) {
            throw new BusinessException(ErrorCode.INVALID_CREDENTIALS, "Email or password is incorrect");
        }
        return tokenService.issueAccessToken(user.get());
    }

    static String normalizeEmail(String email) {
        return email.trim().toLowerCase(Locale.ROOT);
    }

    private static BusinessException emailTaken() {
        return new BusinessException(ErrorCode.EMAIL_ALREADY_REGISTERED, "An account with this email already exists");
    }
}
