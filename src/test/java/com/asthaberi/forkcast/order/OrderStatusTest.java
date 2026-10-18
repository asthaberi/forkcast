package com.asthaberi.forkcast.order;

import static com.asthaberi.forkcast.order.OrderActor.CUSTOMER;
import static com.asthaberi.forkcast.order.OrderActor.RESTAURANT;
import static com.asthaberi.forkcast.order.OrderStatus.ACCEPTED;
import static com.asthaberi.forkcast.order.OrderStatus.CANCELLED;
import static com.asthaberi.forkcast.order.OrderStatus.DELIVERED;
import static com.asthaberi.forkcast.order.OrderStatus.OUT_FOR_DELIVERY;
import static com.asthaberi.forkcast.order.OrderStatus.PLACED;
import static com.asthaberi.forkcast.order.OrderStatus.PREPARING;
import static com.asthaberi.forkcast.order.OrderStatus.REJECTED;
import static org.assertj.core.api.Assertions.assertThat;

import java.util.EnumSet;
import java.util.Set;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

class OrderStatusTest {

    // Every allowed move. Anything not listed here must be rejected.
    private static final Set<String> ALLOWED = Set.of(
            "PLACED>ACCEPTED:RESTAURANT", "PLACED>REJECTED:RESTAURANT", "PLACED>CANCELLED:CUSTOMER",
            "ACCEPTED>PREPARING:RESTAURANT", "PREPARING>OUT_FOR_DELIVERY:RESTAURANT",
            "OUT_FOR_DELIVERY>DELIVERED:RESTAURANT");

    @Test
    void onlyTheListedMovesAreAllowedAcrossAllCombinations() {
        int checked = 0;
        for (OrderStatus from : OrderStatus.values()) {
            for (OrderStatus to : OrderStatus.values()) {
                for (OrderActor actor : OrderActor.values()) {
                    boolean expected = ALLOWED.contains(from + ">" + to + ":" + actor);
                    assertThat(from.canMoveTo(to, actor)).as("%s -> %s by %s", from, to, actor).isEqualTo(expected);
                    checked++;
                }
            }
        }
        assertThat(checked).isEqualTo(7 * 7 * 2);
    }

    @ParameterizedTest
    @CsvSource({"PLACED,ACCEPTED", "ACCEPTED,DELIVERED", "PREPARING,PLACED", "DELIVERED,CANCELLED"})
    void customersCantMoveOrdersAlongOrSkipSteps(OrderStatus from, OrderStatus to) {
        assertThat(from.canMoveTo(to, CUSTOMER)).isFalse();
    }

    @Test
    void restaurantCantCancelOnTheCustomersBehalf() {
        assertThat(PLACED.canMoveTo(CANCELLED, RESTAURANT)).isFalse();
        assertThat(PLACED.nextFor(RESTAURANT)).containsExactlyInAnyOrder(ACCEPTED, REJECTED);
        assertThat(PLACED.nextFor(CUSTOMER)).containsExactly(CANCELLED);
    }

    @Test
    void terminalStatesAreExactlyTheEnds() {
        Set<OrderStatus> terminal = EnumSet.noneOf(OrderStatus.class);
        for (OrderStatus status : OrderStatus.values()) {
            if (status.isTerminal()) {
                terminal.add(status);
            }
        }
        assertThat(terminal).containsExactlyInAnyOrder(DELIVERED, CANCELLED, REJECTED);
        assertThat(OUT_FOR_DELIVERY.isTerminal()).isFalse();
        assertThat(PREPARING.nextFor(RESTAURANT)).containsExactly(OUT_FOR_DELIVERY);
    }
}
