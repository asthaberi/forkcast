package com.asthaberi.forkcast.restaurant;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.time.LocalTime;
import java.util.List;

import com.asthaberi.forkcast.common.exception.ResourceNotFoundException;
import com.asthaberi.forkcast.restaurant.dto.RestaurantResponse;
import com.asthaberi.forkcast.restaurant.dto.RestaurantSearchResponse;
import com.asthaberi.forkcast.security.TestJwt;
import com.asthaberi.forkcast.security.WithApiSecurity;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.http.MediaType;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

/**
 * Web-layer slice test: only the controller, JSON handling and exception handler are loaded;
 * the service is a mock, so no database is needed.
 */
@WebMvcTest(RestaurantController.class)
@WithApiSecurity
class RestaurantControllerTest {

    @Autowired
    private MockMvc mockMvc;

    @MockitoBean
    private RestaurantService restaurantService;

    @Test
    void searchReturnsRestaurants() throws Exception {
        when(restaurantService.searchNearby(26.2124, 78.1772, null, null, false))
                .thenReturn(new RestaurantSearchResponse(5.0, List.of(sampleResponse(0.42))));

        mockMvc.perform(get("/api/v1/restaurants")
                        .param("latitude", "26.2124")
                        .param("longitude", "78.1772"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.radiusKm").value(5.0))
                .andExpect(jsonPath("$.count").value(1))
                .andExpect(jsonPath("$.restaurants[0].name").value("Spice Route Kitchen"))
                .andExpect(jsonPath("$.restaurants[0].distanceKm").value(0.42))
                .andExpect(jsonPath("$.restaurants[0].opensAt").value("11:00:00"));
    }

    @Test
    void searchRejectsOutOfRangeCoordinates() throws Exception {
        mockMvc.perform(get("/api/v1/restaurants")
                        .param("latitude", "120")
                        .param("longitude", "78.1772"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.title").value("Invalid request"))
                .andExpect(jsonPath("$.errors.latitude").exists());
    }

    @Test
    void searchRequiresLatitude() throws Exception {
        mockMvc.perform(get("/api/v1/restaurants").param("longitude", "78.1772"))
                .andExpect(status().isBadRequest());
    }

    @Test
    void getByIdReturns404WhenMissing() throws Exception {
        when(restaurantService.getById("nope")).thenThrow(new ResourceNotFoundException("Restaurant", "nope"));

        mockMvc.perform(get("/api/v1/restaurants/nope"))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.detail").value("Restaurant not found with id 'nope'"));
    }

    @Test
    void createReturns201WithLocation() throws Exception {
        when(restaurantService.create(any(), eq("owner-1"))).thenReturn(sampleResponse(null));

        mockMvc.perform(post("/api/v1/restaurants").with(TestJwt.owner("owner-1"))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "name": "Spice Route Kitchen",
                                  "address": "City Centre",
                                  "city": "Gwalior",
                                  "latitude": 26.2150,
                                  "longitude": 78.1800,
                                  "cuisines": ["North Indian"],
                                  "opensAt": "11:00",
                                  "closesAt": "23:00"
                                }
                                """))
                .andExpect(status().isCreated())
                .andExpect(header().string("Location", "http://localhost/api/v1/restaurants/r1"))
                .andExpect(jsonPath("$.id").value("r1"))
                .andExpect(jsonPath("$.distanceKm").doesNotExist());
    }

    @Test
    void createValidatesBody() throws Exception {
        mockMvc.perform(post("/api/v1/restaurants").with(TestJwt.owner("owner-1"))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                { "name": "", "cuisines": [] }
                                """))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errors.name").exists())
                .andExpect(jsonPath("$.errors.latitude").exists())
                .andExpect(jsonPath("$.errors.cuisines").exists());
    }

    @Test
    void searchIsPublicButCreatingNeedsAnOwnerOrAdmin() throws Exception {
        mockMvc.perform(post("/api/v1/restaurants").contentType(MediaType.APPLICATION_JSON).content("{}"))
                .andExpect(status().isUnauthorized());
        mockMvc.perform(post("/api/v1/restaurants").with(TestJwt.customer("c1"))
                        .contentType(MediaType.APPLICATION_JSON).content("{}"))
                .andExpect(status().isForbidden());
    }

    private static RestaurantResponse sampleResponse(Double distanceKm) {
        return new RestaurantResponse("r1", "Spice Route Kitchen", "City Centre", "Gwalior", 26.2150, 78.1800,
                List.of("north indian"), 4.4, LocalTime.of(11, 0), LocalTime.of(23, 0), true, distanceKm, null);
    }
}
