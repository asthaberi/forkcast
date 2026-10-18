package com.asthaberi.forkcast.auth.dto;

/**
 * @param expiresIn seconds until the token expires
 */
public record TokenResponse(String accessToken, String tokenType, long expiresIn) {
}
