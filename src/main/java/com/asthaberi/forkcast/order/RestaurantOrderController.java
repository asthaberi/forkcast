package com.asthaberi.forkcast.order;

import java.util.Set;

import com.asthaberi.forkcast.common.web.PageResponse;
import com.asthaberi.forkcast.order.dto.OrderResponse;
import com.asthaberi.forkcast.order.dto.UpdateOrderStatusRequest;
import com.asthaberi.forkcast.order.events.OrderEvent;
import com.asthaberi.forkcast.security.RestaurantAccess;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import lombok.RequiredArgsConstructor;
import org.springframework.http.MediaType;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

/** The restaurant side of orders: the owner's order queue, status updates and a live feed. */
@RestController
@Tag(name = "Restaurant orders", description = "A restaurant's incoming orders, for its owner")
@RequestMapping("/api/v1/restaurants/{restaurantId}/orders")
@RequiredArgsConstructor
public class RestaurantOrderController {

    private final OrderLifecycleService lifecycleService;

    @PreAuthorize(RestaurantAccess.CAN_MANAGE)
    @GetMapping
    @Operation(summary = "List the restaurant's orders, optionally by status")
    public PageResponse<OrderResponse> listOrders(@PathVariable String restaurantId,
                                                  @RequestParam(required = false) Set<OrderStatus> status,
                                                  @RequestParam(defaultValue = "0") @Min(0) int page,
                                                  @RequestParam(defaultValue = "20") @Min(1) @Max(100) int size) {
        return lifecycleService.restaurantOrders(restaurantId, status == null ? Set.of() : status, page, size);
    }

    /** Moves an order along: ACCEPTED or REJECTED, then PREPARING, OUT_FOR_DELIVERY, DELIVERED. */
    @PreAuthorize(RestaurantAccess.CAN_MANAGE)
    @PostMapping("/{orderId}/status")
    @Operation(summary = "Move an order to its next status")
    @ApiResponse(responseCode = "409",
            description = "INVALID_STATUS_TRANSITION (includes the allowed next statuses)")
    public OrderResponse updateStatus(@PathVariable String restaurantId, @PathVariable String orderId,
                                      @Valid @RequestBody UpdateOrderStatusRequest request) {
        return lifecycleService.updateByRestaurant(restaurantId, orderId, request.status());
    }

    @PreAuthorize(RestaurantAccess.CAN_MANAGE)
    @GetMapping(path = "/events", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    @Operation(summary = "Live feed of new orders and status changes (Server-Sent Events)")
    @ApiResponse(responseCode = "200",
            description = "`text/event-stream` of `order-status` events; each event's data is an OrderEvent",
            content = @Content(mediaType = "text/event-stream", schema = @Schema(implementation = OrderEvent.class)))
    public SseEmitter events(@PathVariable String restaurantId) {
        return lifecycleService.followRestaurant(restaurantId);
    }
}
