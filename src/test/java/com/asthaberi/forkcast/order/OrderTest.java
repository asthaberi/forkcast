package com.asthaberi.forkcast.order;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

import com.asthaberi.forkcast.common.exception.BusinessException;
import com.asthaberi.forkcast.common.exception.ErrorCode;
import com.asthaberi.forkcast.order.Order.StatusChange;
import org.junit.jupiter.api.Test;

class OrderTest {

    private static final Instant PLACED_AT = Instant.parse("2026-10-18T07:00:00Z");

    @Test
    void newOrderIsPlacedWithHistoryAndSnapshotLines() {
        List<OrderLine> lines = new ArrayList<>(List.of(
                new OrderLine("dosa", "Masala Dosa", new BigDecimal("129.00"), 2, new BigDecimal("258.00"))));
        Order order = order(lines);
        lines.clear();

        assertThat(order.getStatus()).isEqualTo(OrderStatus.PLACED);
        assertThat(order.getStatusHistory()).containsExactly(new StatusChange(OrderStatus.PLACED, PLACED_AT));
        // Defensive copy: changing the caller's list doesn't change the order
        assertThat(order.getLines()).hasSize(1);
        assertThat(order.itemCount()).isEqualTo(2);
    }

    @Test
    void placedOrderCanBeCancelledOnce() {
        Order order = order(List.of());
        Instant cancelledAt = PLACED_AT.plusSeconds(60);

        order.cancel(cancelledAt);

        assertThat(order.getStatus()).isEqualTo(OrderStatus.CANCELLED);
        assertThat(order.getStatusHistory()).last().isEqualTo(new StatusChange(OrderStatus.CANCELLED, cancelledAt));
        assertThatThrownBy(() -> order.cancel(cancelledAt))
                .isInstanceOfSatisfying(BusinessException.class,
                        ex -> assertThat(ex.code()).isEqualTo(ErrorCode.ORDER_NOT_CANCELLABLE));
    }

    private static Order order(List<OrderLine> lines) {
        return Order.builder().userId("u1").idempotencyKey("k1").restaurantId("r1").restaurantName("Dosa Junction")
                .lines(lines).subtotal(BigDecimal.ZERO).deliveryFee(BigDecimal.ZERO).tax(BigDecimal.ZERO)
                .total(BigDecimal.ZERO).deliveryAddress(new DeliveryAddress("Home", 26.21, 78.18))
                .placedAt(PLACED_AT).build();
    }
}
