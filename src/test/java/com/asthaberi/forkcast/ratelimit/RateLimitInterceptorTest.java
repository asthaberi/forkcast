package com.asthaberi.forkcast.ratelimit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.web.method.HandlerMethod;

class RateLimitInterceptorTest {

    private final List<String> calls = new ArrayList<>();
    private boolean allow = true;
    private final RateLimiter limiter = (policy, key) -> {
        calls.add(policy + "|" + key);
        return new RateLimitDecision(allow, 10, allow ? 9 : 0, allow ? Duration.ZERO : Duration.ofMillis(4_200));
    };
    private final RateLimitInterceptor interceptor = new RateLimitInterceptor(limiter);

    @AfterEach
    void clearSecurityContext() {
        SecurityContextHolder.clearContext();
    }

    @Test
    void ipLimitedEndpointUsesTheConnectionAddressNotForwardedHeaders() throws Exception {
        MockHttpServletRequest request = new MockHttpServletRequest();
        request.setRemoteAddr("203.0.113.7");
        request.addHeader("X-Forwarded-For", "1.1.1.1");

        assertThat(interceptor.preHandle(request, new MockHttpServletResponse(), handler("login"))).isTrue();

        assertThat(calls).containsExactly("login|ip:203.0.113.7");
    }

    @Test
    void userLimitedEndpointUsesTheSignedInUser() throws Exception {
        SecurityContextHolder.getContext().setAuthentication(new UsernamePasswordAuthenticationToken("user-42", null,
                List.of(new SimpleGrantedAuthority("ROLE_CUSTOMER"))));

        interceptor.preHandle(new MockHttpServletRequest(), new MockHttpServletResponse(), handler("placeOrder"));

        assertThat(calls).containsExactly("place-order|user:user-42");
    }

    @Test
    void endpointsWithoutTheAnnotationAreNotChecked() throws Exception {
        interceptor.preHandle(new MockHttpServletRequest(), new MockHttpServletResponse(), handler("unlimited"));

        assertThat(calls).isEmpty();
    }

    @Test
    void exhaustedBucketThrowsWithRetryAfterRoundedUp() {
        allow = false;

        assertThatThrownBy(() -> interceptor.preHandle(new MockHttpServletRequest(), new MockHttpServletResponse(),
                handler("login")))
                .isInstanceOfSatisfying(RateLimitExceededException.class,
                        ex -> assertThat(ex.decision().retryAfterSeconds()).isEqualTo(5));
    }

    private static HandlerMethod handler(String method) throws NoSuchMethodException {
        return new HandlerMethod(new Endpoints(), Endpoints.class.getMethod(method));
    }

    static class Endpoints {

        @RateLimit(policy = "login", by = RateLimit.KeyType.IP)
        public void login() {
        }

        @RateLimit(policy = "place-order", by = RateLimit.KeyType.USER)
        public void placeOrder() {
        }

        public void unlimited() {
        }
    }
}
