package com.asthaberi.forkcast.ratelimit;

import java.io.IOException;
import java.net.URI;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ProblemDetail;
import org.springframework.web.filter.OncePerRequestFilter;
import tools.jackson.databind.json.JsonMapper;

/**
 * API-wide fair-use limit: every /api request takes one token from the caller's bucket, per user when
 * signed in, per IP otherwise. Runs in the security filter chain right after the bearer token is
 * verified, so it can key on the real user, and rejects before any controller or database work.
 *
 * Every response gets RateLimit-Limit and RateLimit-Remaining headers so well-behaved clients can slow down.
 */
public class ApiRateLimitFilter extends OncePerRequestFilter {

    public static final String POLICY = "api";

    private final RateLimiter rateLimiter;
    private final JsonMapper jsonMapper;

    public ApiRateLimitFilter(RateLimiter rateLimiter, JsonMapper jsonMapper) {
        this.rateLimiter = rateLimiter;
        this.jsonMapper = jsonMapper;
    }

    @Override
    protected boolean shouldNotFilter(HttpServletRequest request) {
        return !request.getRequestURI().startsWith("/api/");
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {
        RateLimitDecision decision = rateLimiter.tryConsume(POLICY, RateLimitKeys.userOrIp(request));
        if (decision.limit() != Integer.MAX_VALUE) {
            response.setHeader("RateLimit-Limit", String.valueOf(decision.limit()));
            response.setHeader("RateLimit-Remaining", String.valueOf(decision.remaining()));
        }
        if (decision.allowed()) {
            chain.doFilter(request, response);
            return;
        }
        ProblemDetail problem = ProblemDetail.forStatusAndDetail(HttpStatus.TOO_MANY_REQUESTS,
                "Too many requests. Try again in " + decision.retryAfterSeconds() + " s.");
        problem.setTitle("Too many requests");
        problem.setInstance(URI.create(request.getRequestURI()));
        problem.setProperty("code", "RATE_LIMITED");
        problem.setProperty("retryAfterSeconds", decision.retryAfterSeconds());
        response.setStatus(HttpStatus.TOO_MANY_REQUESTS.value());
        response.setHeader(HttpHeaders.RETRY_AFTER, String.valueOf(decision.retryAfterSeconds()));
        response.setContentType(MediaType.APPLICATION_PROBLEM_JSON_VALUE);
        jsonMapper.writeValue(response.getOutputStream(), problem);
    }
}
