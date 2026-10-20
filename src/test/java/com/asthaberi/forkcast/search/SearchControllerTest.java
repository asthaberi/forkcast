package com.asthaberi.forkcast.search;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.math.BigDecimal;
import java.util.List;

import com.asthaberi.forkcast.common.exception.BusinessException;
import com.asthaberi.forkcast.common.exception.ErrorCode;
import com.asthaberi.forkcast.search.dto.DishSearchResponse;
import com.asthaberi.forkcast.security.TestJwt;
import com.asthaberi.forkcast.security.WithApiSecurity;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

@WebMvcTest({SearchController.class, AdminSearchController.class})
@WithApiSecurity
class SearchControllerTest {

    @Autowired
    private MockMvc mockMvc;

    @MockitoBean
    private DishSearchService searchService;
    @MockitoBean
    private DishIndexer indexer;
    @MockitoBean
    private DishVectorIndex index;

    @Test
    void searchIsPublicAndPassesFiltersThrough() throws Exception {
        when(searchService.search(any())).thenReturn(new DishSearchResponse("light breakfast", null, 0, List.of()));

        mockMvc.perform(get("/api/v1/search/dishes").param("q", "  light breakfast ").param("vegetarian", "true")
                        .param("maxPrice", "150"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.query").value("light breakfast"));

        verify(searchService).search(new DishSearchQuery("light breakfast", null, null, null, true,
                new BigDecimal("150"), null));
    }

    @Test
    void missingKeyIsA503WithItsOwnCode() throws Exception {
        when(searchService.search(any())).thenThrow(new BusinessException(ErrorCode.AI_SEARCH_UNAVAILABLE, "off"));

        mockMvc.perform(get("/api/v1/search/dishes").param("q", "dosa"))
                .andExpect(status().isServiceUnavailable())
                .andExpect(jsonPath("$.code").value("AI_SEARCH_UNAVAILABLE"));
    }

    @Test
    void rejectsHalfALocationAndBlankQueries() throws Exception {
        mockMvc.perform(get("/api/v1/search/dishes").param("q", "dosa").param("latitude", "26.2"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("INCOMPLETE_LOCATION"));
        mockMvc.perform(get("/api/v1/search/dishes").param("q", " "))
                .andExpect(status().isBadRequest());
        verifyNoInteractions(searchService);
    }

    @Test
    void reindexIsAdminOnly() throws Exception {
        mockMvc.perform(post("/api/v1/admin/search/reindex")).andExpect(status().isUnauthorized());
        mockMvc.perform(post("/api/v1/admin/search/reindex").with(TestJwt.owner("o1"))).andExpect(status().isForbidden());

        when(index.isAvailable()).thenReturn(true);
        when(indexer.reindexAll()).thenReturn(62);
        mockMvc.perform(post("/api/v1/admin/search/reindex").with(TestJwt.as("a1", "ADMIN")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.indexed").value(62));
    }
}
