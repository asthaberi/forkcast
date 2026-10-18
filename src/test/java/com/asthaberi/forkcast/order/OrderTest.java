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
        assertThat(order.getStatusHistory())
                .containsExactly(new StatusChange(OrderStatus.PLACED, PLACED_AT, OrderActor.CUSTOMER));
        // Defensive copy: changing the caller's list doesn't change the order
        assertThat(order.getLines()).hasSize(1);
        assertThat(order.itemCount()).isEqualTo(2);
    }

    @Test
    void walksTheHappyPathRecordingWhoDidWhatAndWhen() {
        Order order = order(List.of());

        order.transitionTo(OrderStatus.ACCEPTED, OrderActor.RESTAURANT, PLACED_AT.plusSeconds(60));
        order.transitionTo(OrderStatus.PREPARING, OrderActor.RESTAURANT, PLACED_AT.plusSeconds(120));
        order.transitionTo(OrderStatus.OUT_FOR_DELIVERY, OrderActor.RESTAURANT, PLACED_AT.plusSeconds(900));
        order.transitionTo(OrderStatus.DELIVERED, OrderActor.RESTAURANT, PLACED_AT.plusSeconds(1800));

        assertThat(order.getStatus()).isEqualTo(OrderStatus.DELIVERED);
        assertThat(order.getStatusHistory()).extracting(StatusChange::status).containsExactly(
                OrderStatus.PLACED, OrderStatus.ACCEPTED, OrderStatus.PREPARING, OrderStatus.OUT_FOR_DELIVERY,
                OrderStatus.DELIVERED);
        assertThat(order.lastChangedAt()).isEqualTo(PLACED_AT.plusSeconds(1800));
    }

    @Test
    void customerCanCancelOnlyBeforeTheRestaurantAccepts() {
        Order cancelled = order(List.of());
        cancelled.transitionTo(OrderStatus.CANCELLED, OrderActor.CUSTOMER, PLACED_AT.plusSeconds(30));
        assertThat(cancelled.getStatus()).isEqualTo(OrderStatus.CANCELLED);

        Order accepted = order(List.of());
        accepted.transitionTo(OrderStatus.ACCEPTED, OrderActor.RESTAURANT, PLACED_AT.plusSeconds(30));
        assertThatThrownBy(() -> accepted.transitionTo(OrderStatus.CANCELLED, OrderActor.CUSTOMER, PLACED_AT))
                .isInstanceOfSatisfying(BusinessException.class,
                        ex -> assertThat(ex.code()).isEqualTo(ErrorCode.ORDER_NOT_CANCELLABLE));
    }

    @Test
    void invalidMoveIsRejectedWithTheAllowedNextSteps() {
        Order order = order(List.of());

        assertThatThrownBy(() -> order.transitionTo(OrderStatus.DELIVERED, OrderActor.RESTAURANT, PLACED_AT))
                .isInstanceOfSatisfying(BusinessException.class, ex -> {
                    assertThat(ex.code()).isEqualTo(ErrorCode.INVALID_STATUS_TRANSITION);
                    assertThat(ex.details()).containsEntry("from", OrderStatus.PLACED);
                    assertThat(ex.details().get("allowed"))
                            .isEqualTo(java.util.EnumSet.of(OrderStatus.ACCEPTED, OrderStatus.REJECTED));
                });
        assertThat(order.getStatusHistory()).hasSize(1);
    }

    private static Order order(List<OrderLine> lines) {
        return Order.builder().userId("u1").idempotencyKey("k1").restaurantId("r1").restaurantName("Dosa Junction")
                .lines(lines).subtotal(BigDecimal.ZERO).deliveryFee(BigDecimal.ZERO).tax(BigDecimal.ZERO)
                .total(BigDecimal.ZERO).deliveryAddress(new DeliveryAddress("Home", 26.21, 78.18))
                .placedAt(PLACED_AT).build();
    }
}
