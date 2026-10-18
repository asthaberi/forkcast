package com.asthaberi.forkcast.order;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.util.List;
import java.util.Set;

import com.asthaberi.forkcast.common.web.PageResponse;
import com.asthaberi.forkcast.security.RestaurantAccess;
import com.asthaberi.forkcast.security.TestJwt;
import com.asthaberi.forkcast.security.WithApiSecurity;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.http.MediaType;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

@WebMvcTest(RestaurantOrderController.class)
@WithApiSecurity
class RestaurantOrderControllerTest {

    @Autowired
    private MockMvc mockMvc;

    @MockitoBean
    private OrderLifecycleService lifecycleService;

    @MockitoBean(name = "restaurantAccess")
    private RestaurantAccess restaurantAccess;

    @BeforeEach
    void ownerOwnsR1Only() {
        when(restaurantAccess.canManage("r1")).thenReturn(true);
    }

    @Test
    void ordersAreNotPublicEvenThoughOtherRestaurantGetsAre() throws Exception {
        // GET /api/v1/restaurants/** is public, so this checks the more specific rule wins
        mockMvc.perform(get("/api/v1/restaurants/r1/orders")).andExpect(status().isUnauthorized());
        mockMvc.perform(get("/api/v1/restaurants/r1/orders").with(TestJwt.customer("c1")))
                .andExpect(status().isForbidden());
        verifyNoInteractions(lifecycleService);
    }

    @Test
    void ownerSeesOnlyTheirOwnRestaurantsOrders() throws Exception {
        when(lifecycleService.restaurantOrders(eq("r1"), any(), anyInt(), anyInt()))
                .thenReturn(new PageResponse<>(List.of(), 0, 20, 0, 0));

        mockMvc.perform(get("/api/v1/restaurants/r1/orders").param("status", "PLACED", "ACCEPTED")
                        .with(TestJwt.owner("o1")))
                .andExpect(status().isOk());
        mockMvc.perform(get("/api/v1/restaurants/r2/orders").with(TestJwt.owner("o1")))
                .andExpect(status().isForbidden());

        verify(lifecycleService).restaurantOrders("r1", Set.of(OrderStatus.PLACED, OrderStatus.ACCEPTED), 0, 20);
    }

    @Test
    void statusUpdateValidatesTheStatusValue() throws Exception {
        mockMvc.perform(post("/api/v1/restaurants/r1/orders/o1/status").with(TestJwt.owner("o1"))
                        .contentType(MediaType.APPLICATION_JSON).content("{ }"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errors.status").exists());
        mockMvc.perform(post("/api/v1/restaurants/r1/orders/o1/status").with(TestJwt.owner("o1"))
                        .contentType(MediaType.APPLICATION_JSON).content("{ \"status\": \"TELEPORTED\" }"))
                .andExpect(status().isBadRequest());
        verifyNoInteractions(lifecycleService);
    }
}
