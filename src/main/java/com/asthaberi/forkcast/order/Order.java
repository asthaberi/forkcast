package com.asthaberi.forkcast.order;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import com.asthaberi.forkcast.common.exception.BusinessException;
import com.asthaberi.forkcast.common.exception.ErrorCode;
import lombok.AccessLevel;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import org.springframework.data.annotation.Id;
import org.springframework.data.annotation.Version;
import org.springframework.data.mongodb.core.index.CompoundIndex;
import org.springframework.data.mongodb.core.mapping.Document;

/**
 * A placed order: an immutable snapshot of what was bought and what it cost, plus its status.
 */
@Document(collection = "orders")
// The same idempotency key can only ever create one order per user, even under concurrent requests.
@CompoundIndex(name = "user_idempotency_key", def = "{'userId': 1, 'idempotencyKey': 1}", unique = true)
// Supports "my orders, newest first" without an in-memory sort.
@CompoundIndex(name = "user_placed_at", def = "{'userId': 1, 'placedAt': -1}")
// Supports a restaurant's order queue filtered by status, newest first.
@CompoundIndex(name = "restaurant_status_placed_at", def = "{'restaurantId': 1, 'status': 1, 'placedAt': -1}")
@Getter
@NoArgsConstructor(access = AccessLevel.PRIVATE)
public class Order {

    @Id
    private String id;

    private String userId;

    private String idempotencyKey;

    private String restaurantId;

    private String restaurantName;

    private List<OrderLine> lines;

    private BigDecimal subtotal;

    private BigDecimal deliveryFee;

    private BigDecimal tax;

    private BigDecimal total;

    private DeliveryAddress deliveryAddress;

    private OrderStatus status;

    private List<StatusChange> statusHistory;

    private Instant placedAt;

    // Guards status changes: a customer cancelling while the restaurant accepts can't both succeed.
    @Version
    private Long version;

    @Builder
    private static Order place(String userId, String idempotencyKey, String restaurantId, String restaurantName,
                               List<OrderLine> lines, BigDecimal subtotal, BigDecimal deliveryFee, BigDecimal tax,
                               BigDecimal total, DeliveryAddress deliveryAddress, Instant placedAt) {
        Order order = new Order();
        order.userId = userId;
        order.idempotencyKey = idempotencyKey;
        order.restaurantId = restaurantId;
        order.restaurantName = restaurantName;
        order.lines = List.copyOf(lines);
        order.subtotal = subtotal;
        order.deliveryFee = deliveryFee;
        order.tax = tax;
        order.total = total;
        order.deliveryAddress = deliveryAddress;
        order.status = OrderStatus.PLACED;
        order.statusHistory = new ArrayList<>(
                List.of(new StatusChange(OrderStatus.PLACED, placedAt, OrderActor.CUSTOMER)));
        order.placedAt = placedAt;
        return order;
    }

    /**
     * Moves the order to a new status if the state machine allows this actor to; see {@link OrderStatus}.
     */
    public void transitionTo(OrderStatus target, OrderActor actor, Instant at) {
        if (!status.canMoveTo(target, actor)) {
            if (target == OrderStatus.CANCELLED && actor == OrderActor.CUSTOMER) {
                throw new BusinessException(ErrorCode.ORDER_NOT_CANCELLABLE,
                        "Order can no longer be cancelled (status " + status + ")");
            }
            throw new BusinessException(ErrorCode.INVALID_STATUS_TRANSITION,
                    "Can't move an order from " + status + " to " + target,
                    Map.of("from", status, "to", target, "allowed", status.nextFor(actor)));
        }
        status = target;
        statusHistory.add(new StatusChange(target, at, actor));
    }

    public Instant lastChangedAt() {
        return statusHistory.getLast().at();
    }

    public int itemCount() {
        return lines.stream().mapToInt(OrderLine::quantity).sum();
    }

    public record StatusChange(OrderStatus status, Instant at, OrderActor actor) {
    }
}
