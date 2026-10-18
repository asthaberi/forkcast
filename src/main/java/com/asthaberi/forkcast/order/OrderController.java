package com.asthaberi.forkcast.order;

import java.net.URI;

import com.asthaberi.forkcast.common.web.CurrentUser;
import com.asthaberi.forkcast.common.web.PageResponse;
import com.asthaberi.forkcast.order.OrderService.OrderPlacement;
import com.asthaberi.forkcast.order.dto.OrderResponse;
import com.asthaberi.forkcast.order.dto.OrderSummary;
import com.asthaberi.forkcast.order.dto.PlaceOrderRequest;
import com.asthaberi.forkcast.order.events.OrderEvent;
import com.asthaberi.forkcast.ratelimit.RateLimit;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import lombok.RequiredArgsConstructor;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;
import org.springframework.web.servlet.support.ServletUriComponentsBuilder;

@RestController
@Tag(name = "Orders", description = "The signed-in customer's orders")
@RequestMapping("/api/v1/orders")
@RequiredArgsConstructor
public class OrderController {

    static final String IDEMPOTENCY_KEY_HEADER = "Idempotency-Key";
    static final String REPLAYED_HEADER = "Idempotent-Replayed";

    private final OrderService orderService;
    private final OrderLifecycleService lifecycleService;

    /**
     * Places an order from the current cart. Returns 201 for a new order, or 200 with
     * {@code Idempotent-Replayed: true} and the original order when the key was already used.
     */
    @RateLimit(policy = "place-order", by = RateLimit.KeyType.USER)
    @PostMapping
    @Operation(summary = "Place an order from the cart (idempotent per Idempotency-Key)")
    @ApiResponse(responseCode = "201", description = "Order placed")
    @ApiResponse(responseCode = "200",
            description = "Idempotent replay: this key already placed an order, which is returned (header "
                    + "Idempotent-Replayed: true)")
    @ApiResponse(responseCode = "409",
            description = "CART_EMPTY, CART_HAS_UNAVAILABLE_ITEMS, RESTAURANT_CLOSED or CONCURRENT_MODIFICATION")
    @ApiResponse(responseCode = "422",
            description = "OUT_OF_DELIVERY_RANGE (includes distanceKm and maxDistanceKm)")
    public ResponseEntity<OrderResponse> placeOrder(
            @CurrentUser String userId,
            @RequestHeader(IDEMPOTENCY_KEY_HEADER) @NotBlank @Size(max = 100) String idempotencyKey,
            @Valid @RequestBody PlaceOrderRequest request) {
        OrderPlacement placement = orderService.placeOrder(userId, idempotencyKey, request);
        if (!placement.created()) {
            return ResponseEntity.ok().header(REPLAYED_HEADER, "true").body(placement.order());
        }
        URI location = ServletUriComponentsBuilder.fromCurrentRequest()
                .path("/{orderId}")
                .buildAndExpand(placement.order().id())
                .toUri();
        return ResponseEntity.created(location).body(placement.order());
    }

    @GetMapping
    @Operation(summary = "List my orders, newest first")
    public PageResponse<OrderSummary> listOrders(@CurrentUser String userId,
                                                 @RequestParam(defaultValue = "0") @Min(0) int page,
                                                 @RequestParam(defaultValue = "10") @Min(1) @Max(50) int size) {
        return orderService.listOrders(userId, page, size);
    }

    @GetMapping("/{orderId}")
    @Operation(summary = "Get one of my orders with its status history")
    public OrderResponse getOrder(@CurrentUser String userId, @PathVariable String orderId) {
        return orderService.getOrder(userId, orderId);
    }

    @PostMapping("/{orderId}/cancel")
    @Operation(summary = "Cancel an order that is still PLACED")
    @ApiResponse(responseCode = "409", description = "ORDER_NOT_CANCELLABLE: the restaurant already accepted it")
    public OrderResponse cancel(@CurrentUser String userId, @PathVariable String orderId) {
        return lifecycleService.cancelByCustomer(userId, orderId);
    }

    /**
     * Live order tracking over Server-Sent Events: the current status first, then every change until
     * the order is delivered, cancelled or rejected.
     */
    @GetMapping(path = "/{orderId}/events", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    @Operation(summary = "Live order tracking (Server-Sent Events): current status, then every change")
    @ApiResponse(responseCode = "200",
            description = "`text/event-stream` of `order-status` events; each event's data is an OrderEvent",
            content = @Content(mediaType = "text/event-stream", schema = @Schema(implementation = OrderEvent.class)))
    public SseEmitter track(@CurrentUser String userId, @PathVariable String orderId) {
        return lifecycleService.trackOrder(userId, orderId);
    }
}
