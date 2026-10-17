package com.asthaberi.forkcast.restaurant.dto;

import java.time.LocalTime;
import java.util.List;

import jakarta.validation.constraints.DecimalMax;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

public record CreateRestaurantRequest(
        @NotBlank @Size(max = 100) String name,
        @NotBlank @Size(max = 300) String address,
        @NotBlank @Size(max = 100) String city,
        @NotNull @DecimalMin("-90.0") @DecimalMax("90.0") Double latitude,
        @NotNull @DecimalMin("-180.0") @DecimalMax("180.0") Double longitude,
        @NotEmpty List<@NotBlank String> cuisines,
        @NotNull LocalTime opensAt,
        @NotNull LocalTime closesAt,
        // Set directly for now; computed from customer reviews in a later step.
        @DecimalMin("0.0") @DecimalMax("5.0") Double rating,
        String imageUrl) {
}
