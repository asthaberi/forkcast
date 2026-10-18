package com.asthaberi.forkcast.order;

import static com.asthaberi.forkcast.order.OrderActor.CUSTOMER;
import static com.asthaberi.forkcast.order.OrderActor.RESTAURANT;

import java.util.Collections;
import java.util.EnumMap;
import java.util.EnumSet;
import java.util.Map;
import java.util.Set;

/**
 * Order lifecycle as an explicit state machine. Every allowed move is listed once in the table below,
 * together with who may make it; anything not listed is rejected.
 *
 * <pre>
 *            ┌──────────► REJECTED            (restaurant)
 *            │
 *   PLACED ──┼──────────► CANCELLED           (customer)
 *            │
 *            └─► ACCEPTED ─► PREPARING ─► OUT_FOR_DELIVERY ─► DELIVERED   (restaurant)
 * </pre>
 */
public enum OrderStatus {
    PLACED,
    ACCEPTED,
    PREPARING,
    OUT_FOR_DELIVERY,
    DELIVERED,
    CANCELLED,
    REJECTED;

    private static final Map<OrderStatus, Map<OrderStatus, OrderActor>> TRANSITIONS = new EnumMap<>(OrderStatus.class);

    static {
        allow(PLACED, ACCEPTED, RESTAURANT);
        allow(PLACED, REJECTED, RESTAURANT);
        allow(PLACED, CANCELLED, CUSTOMER);
        allow(ACCEPTED, PREPARING, RESTAURANT);
        allow(PREPARING, OUT_FOR_DELIVERY, RESTAURANT);
        allow(OUT_FOR_DELIVERY, DELIVERED, RESTAURANT);
    }

    private static void allow(OrderStatus from, OrderStatus to, OrderActor actor) {
        TRANSITIONS.computeIfAbsent(from, status -> new EnumMap<>(OrderStatus.class)).put(to, actor);
    }

    public boolean canMoveTo(OrderStatus target, OrderActor actor) {
        return actor == TRANSITIONS.getOrDefault(this, Map.of()).get(target);
    }

    /** The statuses this actor may move an order to from here. */
    public Set<OrderStatus> nextFor(OrderActor actor) {
        Set<OrderStatus> next = EnumSet.noneOf(OrderStatus.class);
        TRANSITIONS.getOrDefault(this, Map.of()).forEach((target, who) -> {
            if (who == actor) {
                next.add(target);
            }
        });
        return Collections.unmodifiableSet(next);
    }

    /** No further changes possible: the live-tracking stream closes when an order gets here. */
    public boolean isTerminal() {
        return !TRANSITIONS.containsKey(this);
    }
}
