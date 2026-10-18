package com.asthaberi.forkcast.auth.dto;

import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

/**
 * @param accountType optional, defaults to CUSTOMER. ADMIN isn't an option, so it can't be self-assigned.
 */
public record RegisterRequest(
        @NotBlank @Size(max = 100) String name,
        @NotBlank @Email @Size(max = 254) String email,
        // BCrypt only uses the first 72 bytes; the byte length is checked again in AuthService
        @NotBlank @Size(min = 8, max = 72) String password,
        AccountType accountType) {

    public enum AccountType {
        CUSTOMER,
        RESTAURANT_OWNER
    }
}
