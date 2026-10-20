package com.asthaberi.forkcast.review;

import com.asthaberi.forkcast.common.web.CurrentUser;
import com.asthaberi.forkcast.common.web.PageResponse;
import com.asthaberi.forkcast.review.dto.ReviewDtos.CreateReviewRequest;
import com.asthaberi.forkcast.review.dto.ReviewDtos.ReviewResponse;
import com.asthaberi.forkcast.review.dto.ReviewSummaryResponse;
import com.asthaberi.forkcast.review.summary.ReviewSummaryService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.security.SecurityRequirements;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

@RestController
@Tag(name = "Reviews", description = "Customer reviews and AI-written review summaries")
@RequestMapping("/api/v1")
@RequiredArgsConstructor
public class ReviewController {

    private final ReviewService reviewService;
    private final ReviewSummaryService summaryService;

    @PostMapping("/orders/{orderId}/review")
    @ResponseStatus(HttpStatus.CREATED)
    @Operation(summary = "Review one of my delivered orders (once per order)")
    @ApiResponse(responseCode = "201", description = "Review saved; the restaurant's rating is updated")
    @ApiResponse(responseCode = "404", description = "No such order of mine")
    @ApiResponse(responseCode = "409", description = "REVIEW_NOT_ALLOWED (not delivered yet) or ALREADY_REVIEWED")
    public ReviewResponse create(@CurrentUser String userId, @PathVariable String orderId,
                                 @Valid @RequestBody CreateReviewRequest request) {
        return reviewService.create(userId, orderId, request);
    }

    @GetMapping("/restaurants/{restaurantId}/reviews")
    @Operation(summary = "A restaurant's reviews, newest first")
    @SecurityRequirements // public: no token needed
    public PageResponse<ReviewResponse> list(@PathVariable String restaurantId,
                                             @RequestParam(defaultValue = "0") @Min(0) int page,
                                             @RequestParam(defaultValue = "10") @Min(1) @Max(50) int size) {
        return reviewService.list(restaurantId, page, size);
    }

    @GetMapping("/restaurants/{restaurantId}/reviews/summary")
    @Operation(summary = "Rating breakdown plus an AI-written summary of what reviewers say (Gemini)")
    @SecurityRequirements // public: no token needed
    @ApiResponse(responseCode = "200",
            description = "status READY with a summary, or NOT_ENOUGH_REVIEWS (fewer than 3) with only the numbers")
    @ApiResponse(responseCode = "503",
            description = "AI_SUMMARY_UNAVAILABLE: no Gemini API key, or the first summary couldn't be written")
    public ReviewSummaryResponse summary(@PathVariable String restaurantId) {
        return summaryService.get(restaurantId);
    }
}
