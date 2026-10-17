package com.asthaberi.forkcast.menu.dto;

import java.math.BigDecimal;
import java.util.List;

import com.asthaberi.forkcast.menu.SpiceLevel;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.Digits;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

public record MenuItemRequest(
        @NotBlank @Size(max = 100) String name,
        @Size(max = 500) String description,
        @NotBlank @Size(max = 50) String category,
        @NotNull @DecimalMin("1.00") @Digits(integer = 6, fraction = 2) BigDecimal price,
        @NotNull Boolean vegetarian,
        // Optional: defaults to NONE
        SpiceLevel spiceLevel,
        List<@NotBlank String> tags,
        // Optional: defaults to true
        Boolean available) {
}
