package com.asthaberi.forkcast.review;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.time.Instant;
import java.util.List;

import com.asthaberi.forkcast.common.exception.BusinessException;
import com.asthaberi.forkcast.common.exception.ErrorCode;
import com.asthaberi.forkcast.common.web.PageResponse;
import com.asthaberi.forkcast.review.dto.ReviewDtos.ReviewResponse;
import com.asthaberi.forkcast.review.summary.ReviewSummaryService;
import com.asthaberi.forkcast.security.TestJwt;
import com.asthaberi.forkcast.security.WithApiSecurity;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.http.MediaType;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

@WebMvcTest(ReviewController.class)
@WithApiSecurity
class ReviewControllerTest {

    private static final String REVIEW = """
            {"rating": 5, "text": "Crisp dosa"}
            """;

    @Autowired
    private MockMvc mockMvc;

    @MockitoBean
    private ReviewService reviewService;
    @MockitoBean
    private ReviewSummaryService summaryService;

    @Test
    void customerReviewsTheirOrder() throws Exception {
        when(reviewService.create(eq("u1"), eq("o1"), any())).thenReturn(
                new ReviewResponse("rv1", 5, "Crisp dosa", "Astha B.", true, Instant.parse("2026-10-20T12:00:00Z")));

        mockMvc.perform(post("/api/v1/orders/o1/review").with(TestJwt.customer("u1"))
                        .contentType(MediaType.APPLICATION_JSON).content(REVIEW))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.authorName").value("Astha B."))
                .andExpect(jsonPath("$.verifiedOrder").value(true));
    }

    @Test
    void onlyCustomersCanReview() throws Exception {
        mockMvc.perform(post("/api/v1/orders/o1/review").contentType(MediaType.APPLICATION_JSON).content(REVIEW))
                .andExpect(status().isUnauthorized());
        mockMvc.perform(post("/api/v1/orders/o1/review").with(TestJwt.owner("o9"))
                        .contentType(MediaType.APPLICATION_JSON).content(REVIEW))
                .andExpect(status().isForbidden());
        verifyNoInteractions(reviewService);
    }

    @Test
    void rejectsOutOfRangeRatingsAndEmptyText() throws Exception {
        mockMvc.perform(post("/api/v1/orders/o1/review").with(TestJwt.customer("u1"))
                        .contentType(MediaType.APPLICATION_JSON).content("{\"rating\": 6, \"text\": \"great\"}"))
                .andExpect(status().isBadRequest());
        mockMvc.perform(post("/api/v1/orders/o1/review").with(TestJwt.customer("u1"))
                        .contentType(MediaType.APPLICATION_JSON).content("{\"rating\": 4, \"text\": \"  \"}"))
                .andExpect(status().isBadRequest());
        verifyNoInteractions(reviewService);
    }

    @Test
    void reviewingTwiceIsAConflict() throws Exception {
        when(reviewService.create(any(), any(), any()))
                .thenThrow(new BusinessException(ErrorCode.ALREADY_REVIEWED, "already"));

        mockMvc.perform(post("/api/v1/orders/o1/review").with(TestJwt.customer("u1"))
                        .contentType(MediaType.APPLICATION_JSON).content(REVIEW))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("ALREADY_REVIEWED"));
    }

    @Test
    void reviewsAndSummaryArePublic() throws Exception {
        when(reviewService.list("r1", 0, 10)).thenReturn(new PageResponse<>(List.of(), 0, 10, 0, 0));
        when(summaryService.get("r1"))
                .thenThrow(new BusinessException(ErrorCode.AI_SUMMARY_UNAVAILABLE, "No Gemini API key configured"));

        mockMvc.perform(get("/api/v1/restaurants/r1/reviews")).andExpect(status().isOk());
        mockMvc.perform(get("/api/v1/restaurants/r1/reviews/summary"))
                .andExpect(status().isServiceUnavailable())
                .andExpect(jsonPath("$.code").value("AI_SUMMARY_UNAVAILABLE"));
    }
}
