package com.asthaberi.forkcast.review.dto;

import java.time.Instant;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

public final class ReviewDtos {

    private ReviewDtos() {
    }

    public record CreateReviewRequest(@Min(1) @Max(5) int rating, @NotBlank @Size(min = 3, max = 1000) String text) {
    }

    /** @param verifiedOrder true when written by a customer whose order from this restaurant was delivered */
    public record ReviewResponse(String id, int rating, String text, String authorName, boolean verifiedOrder,
                                 Instant createdAt) {
    }
}
