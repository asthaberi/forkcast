package com.asthaberi.forkcast.recommendation;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.math.BigDecimal;
import java.util.List;

import com.asthaberi.forkcast.recommendation.dto.RecommendationsResponse;
import com.asthaberi.forkcast.recommendation.dto.RecommendationsResponse.RecommendedDish;
import com.asthaberi.forkcast.recommendation.dto.RecommendationsResponse.Strategy;
import com.asthaberi.forkcast.security.TestJwt;
import com.asthaberi.forkcast.security.WithApiSecurity;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

@WebMvcTest(RecommendationController.class)
@WithApiSecurity
class RecommendationControllerTest {

    @Autowired
    private MockMvc mockMvc;

    @MockitoBean
    private RecommendationService recommendationService;

    @Test
    void recommendsForTheSignedInCustomer() throws Exception {
        when(recommendationService.recommend(eq("u1"), any())).thenReturn(new RecommendationsResponse(
                Strategy.PERSONALIZED, 5.0, false, List.of(),
                List.of(new RecommendedDish("m1", "Medu Vada", "Snacks", new BigDecimal("79.00"), true, "r1",
                        "Dosa Junction", 0.9, true, "Because you ordered Masala Dosa", 0.82))));

        mockMvc.perform(get("/api/v1/recommendations").param("latitude", "26.2124").param("longitude", "78.1772")
                        .with(TestJwt.customer("u1")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.strategy").value("PERSONALIZED"))
                .andExpect(jsonPath("$.forYou[0].reason").value("Because you ordered Masala Dosa"));

        verify(recommendationService).recommend("u1", new RecommendationQuery(26.2124, 78.1772, null, false, 10));
    }

    @Test
    void onlyForCustomers() throws Exception {
        mockMvc.perform(get("/api/v1/recommendations").param("latitude", "26.2").param("longitude", "78.1"))
                .andExpect(status().isUnauthorized());
        mockMvc.perform(get("/api/v1/recommendations").param("latitude", "26.2").param("longitude", "78.1")
                        .with(TestJwt.owner("o1")))
                .andExpect(status().isForbidden());
        verifyNoInteractions(recommendationService);
    }

    @Test
    void validatesLocationAndLimit() throws Exception {
        mockMvc.perform(get("/api/v1/recommendations").param("latitude", "95").param("longitude", "78.1")
                        .with(TestJwt.customer("u1")))
                .andExpect(status().isBadRequest());
        mockMvc.perform(get("/api/v1/recommendations").param("longitude", "78.1").with(TestJwt.customer("u1")))
                .andExpect(status().isBadRequest());
        mockMvc.perform(get("/api/v1/recommendations").param("latitude", "26.2").param("longitude", "78.1")
                        .param("limit", "50").with(TestJwt.customer("u1")))
                .andExpect(status().isBadRequest());
        verifyNoInteractions(recommendationService);
    }
}
