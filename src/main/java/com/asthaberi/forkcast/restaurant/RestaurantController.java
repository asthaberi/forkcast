package com.asthaberi.forkcast.restaurant;

import java.net.URI;

import com.asthaberi.forkcast.common.web.CurrentUser;
import com.asthaberi.forkcast.restaurant.dto.CreateRestaurantRequest;
import com.asthaberi.forkcast.restaurant.dto.RestaurantResponse;
import com.asthaberi.forkcast.restaurant.dto.RestaurantSearchResponse;
import jakarta.validation.Valid;
import jakarta.validation.constraints.DecimalMax;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.Positive;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.servlet.support.ServletUriComponentsBuilder;

@RestController
@RequestMapping("/api/v1/restaurants")
@RequiredArgsConstructor
public class RestaurantController {

    private final RestaurantService restaurantService;

    // Constraint annotations on parameters are checked by Spring MVC's built-in method validation;
    // failures become a 400 response via GlobalExceptionHandler.
    @GetMapping
    public RestaurantSearchResponse searchNearby(
            @RequestParam @DecimalMin("-90.0") @DecimalMax("90.0") double latitude,
            @RequestParam @DecimalMin("-180.0") @DecimalMax("180.0") double longitude,
            @RequestParam(required = false) @Positive @DecimalMax("20.0") Double radiusKm,
            @RequestParam(required = false) String cuisine,
            @RequestParam(defaultValue = "false") boolean openNow) {
        return restaurantService.searchNearby(latitude, longitude, radiusKm, cuisine, openNow);
    }

    @GetMapping("/{id}")
    public RestaurantResponse getById(@PathVariable String id) {
        return restaurantService.getById(id);
    }

    @PostMapping
    public ResponseEntity<RestaurantResponse> create(@CurrentUser String userId,
                                                     @Valid @RequestBody CreateRestaurantRequest request) {
        RestaurantResponse created = restaurantService.create(request, userId);
        URI location = ServletUriComponentsBuilder.fromCurrentRequest()
                .path("/{id}")
                .buildAndExpand(created.id())
                .toUri();
        return ResponseEntity.created(location).body(created);
    }
}
