package com.asthaberi.forkcast.recommendation;

import com.asthaberi.forkcast.common.web.CurrentUser;
import com.asthaberi.forkcast.recommendation.dto.RecommendationsResponse;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.constraints.DecimalMax;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.Positive;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@Tag(name = "Recommendations", description = "Personal dish recommendations from order history and reviews")
@RequestMapping("/api/v1/recommendations")
@RequiredArgsConstructor
public class RecommendationController {

    private final RecommendationService recommendationService;

    @GetMapping
    @Operation(summary = "Dishes to order again, and new dishes picked for my taste, from restaurants that deliver here")
    public RecommendationsResponse recommend(
            @CurrentUser String userId,
            @RequestParam @DecimalMin("-90.0") @DecimalMax("90.0") double latitude,
            @RequestParam @DecimalMin("-180.0") @DecimalMax("180.0") double longitude,
            @RequestParam(required = false) @Positive @DecimalMax("20.0") Double radiusKm,
            @RequestParam(defaultValue = "false") boolean openNow,
            @RequestParam(defaultValue = "10") @Min(1) @Max(20) int limit) {
        return recommendationService.recommend(userId,
                new RecommendationQuery(latitude, longitude, radiusKm, openNow, limit));
    }
}
