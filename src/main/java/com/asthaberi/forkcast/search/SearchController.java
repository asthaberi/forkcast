package com.asthaberi.forkcast.search;

import java.math.BigDecimal;

import com.asthaberi.forkcast.common.exception.BusinessException;
import com.asthaberi.forkcast.common.exception.ErrorCode;
import com.asthaberi.forkcast.ratelimit.RateLimit;
import com.asthaberi.forkcast.search.dto.DishSearchResponse;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.security.SecurityRequirements;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.constraints.DecimalMax;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Positive;
import jakarta.validation.constraints.Size;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@Tag(name = "AI search", description = "Find dishes by meaning, powered by Gemini embeddings")
@RequestMapping("/api/v1/search")
@RequiredArgsConstructor
public class SearchController {

    private final DishSearchService searchService;

    @RateLimit(policy = "ai-search", by = RateLimit.KeyType.USER)
    @GetMapping("/dishes")
    @Operation(summary = "Semantic dish search, e.g. \"light breakfast that isn't spicy\", optionally near a location")
    @SecurityRequirements // public: no token needed
    @ApiResponse(responseCode = "503", description = "AI_SEARCH_UNAVAILABLE: no Gemini API key configured")
    public DishSearchResponse searchDishes(
            @RequestParam @NotBlank @Size(max = 200) String q,
            @RequestParam(required = false) @DecimalMin("-90.0") @DecimalMax("90.0") Double latitude,
            @RequestParam(required = false) @DecimalMin("-180.0") @DecimalMax("180.0") Double longitude,
            @RequestParam(required = false) @Positive @DecimalMax("20.0") Double radiusKm,
            @RequestParam(required = false) Boolean vegetarian,
            @RequestParam(required = false) @Positive BigDecimal maxPrice,
            @RequestParam(required = false) @Min(1) @Max(30) Integer limit) {
        if ((latitude == null) != (longitude == null)) {
            throw new BusinessException(ErrorCode.INCOMPLETE_LOCATION, "Send both latitude and longitude, or neither");
        }
        return searchService.search(new DishSearchQuery(q.trim(), latitude, longitude, radiusKm, vegetarian, maxPrice,
                limit));
    }
}
