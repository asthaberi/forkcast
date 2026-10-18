package com.asthaberi.forkcast.ratelimit;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import lombok.RequiredArgsConstructor;
import org.springframework.web.method.HandlerMethod;
import org.springframework.web.servlet.HandlerInterceptor;

/**
 * Enforces {@link RateLimit} on controller methods. A rejection throws {@link RateLimitExceededException},
 * which GlobalExceptionHandler turns into a 429 with a Retry-After header.
 */
@RequiredArgsConstructor
public class RateLimitInterceptor implements HandlerInterceptor {

    private final RateLimiter rateLimiter;

    @Override
    public boolean preHandle(HttpServletRequest request, HttpServletResponse response, Object handler) {
        if (handler instanceof HandlerMethod method) {
            RateLimit limit = method.getMethodAnnotation(RateLimit.class);
            if (limit != null) {
                String key = limit.by() == RateLimit.KeyType.IP
                        ? RateLimitKeys.ip(request)
                        : RateLimitKeys.userOrIp(request);
                rateLimiter.consumeOrThrow(limit.policy(), key);
            }
        }
        return true;
    }
}
