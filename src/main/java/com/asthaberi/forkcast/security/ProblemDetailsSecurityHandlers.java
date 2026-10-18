package com.asthaberi.forkcast.security;

import java.io.IOException;
import java.net.URI;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ProblemDetail;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.core.AuthenticationException;
import org.springframework.security.oauth2.server.resource.web.BearerTokenAuthenticationEntryPoint;
import org.springframework.security.web.AuthenticationEntryPoint;
import org.springframework.security.web.access.AccessDeniedHandler;
import org.springframework.stereotype.Component;
import tools.jackson.databind.json.JsonMapper;

/**
 * Security rejections happen in servlet filters, before Spring MVC and GlobalExceptionHandler are reached,
 * so these handlers write the same Problem Details JSON the rest of the API uses.
 */
@Component
@RequiredArgsConstructor
public class ProblemDetailsSecurityHandlers implements AuthenticationEntryPoint, AccessDeniedHandler {

    // Sets 401 and the standard WWW-Authenticate: Bearer header (with error="invalid_token" when relevant)
    private final BearerTokenAuthenticationEntryPoint bearerEntryPoint = new BearerTokenAuthenticationEntryPoint();
    private final JsonMapper jsonMapper;

    /** No token, or an invalid one (bad signature, expired, wrong issuer): 401. */
    @Override
    public void commence(HttpServletRequest request, HttpServletResponse response,
                         AuthenticationException authException) throws IOException {
        bearerEntryPoint.commence(request, response, authException);
        write(response, request, HttpStatus.UNAUTHORIZED, "Unauthenticated",
                "Missing, invalid or expired access token. Log in and send it as 'Authorization: Bearer <token>'.");
    }

    /** Valid token, but the user's roles don't allow this request: 403. */
    @Override
    public void handle(HttpServletRequest request, HttpServletResponse response,
                       AccessDeniedException accessDeniedException) throws IOException {
        write(response, request, HttpStatus.FORBIDDEN, "Forbidden", "You don't have permission to do this.");
    }

    private void write(HttpServletResponse response, HttpServletRequest request, HttpStatus status, String title,
                       String detail) throws IOException {
        ProblemDetail problem = ProblemDetail.forStatusAndDetail(status, detail);
        problem.setTitle(title);
        problem.setInstance(URI.create(request.getRequestURI()));
        response.setStatus(status.value());
        response.setContentType(MediaType.APPLICATION_PROBLEM_JSON_VALUE);
        jsonMapper.writeValue(response.getOutputStream(), problem);
    }
}
