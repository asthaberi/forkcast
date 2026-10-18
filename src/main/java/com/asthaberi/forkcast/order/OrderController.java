package com.asthaberi.forkcast.order;

import java.net.URI;

import com.asthaberi.forkcast.common.web.CurrentUser;
import com.asthaberi.forkcast.common.web.PageResponse;
import com.asthaberi.forkcast.order.OrderService.OrderPlacement;
import com.asthaberi.forkcast.order.dto.OrderResponse;
import com.asthaberi.forkcast.order.dto.OrderSummary;
import com.asthaberi.forkcast.order.dto.PlaceOrderRequest;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.servlet.support.ServletUriComponentsBuilder;

@RestController
@RequestMapping("/api/v1/orders")
@RequiredArgsConstructor
public class OrderController {

    static final String IDEMPOTENCY_KEY_HEADER = "Idempotency-Key";
    static final String REPLAYED_HEADER = "Idempotent-Replayed";

    private final OrderService orderService;

    /**
     * Places an order from the current cart. Returns 201 for a new order, or 200 with
     * {@code Idempotent-Replayed: true} and the original order when the key was already used.
     */
    @PostMapping
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
    public PageResponse<OrderSummary> listOrders(@CurrentUser String userId,
                                                 @RequestParam(defaultValue = "0") @Min(0) int page,
                                                 @RequestParam(defaultValue = "10") @Min(1) @Max(50) int size) {
        return orderService.listOrders(userId, page, size);
    }

    @GetMapping("/{orderId}")
    public OrderResponse getOrder(@CurrentUser String userId, @PathVariable String orderId) {
        return orderService.getOrder(userId, orderId);
    }

    @PostMapping("/{orderId}/cancel")
    public OrderResponse cancel(@CurrentUser String userId, @PathVariable String orderId) {
        return orderService.cancel(userId, orderId);
    }
}
