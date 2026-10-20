package com.asthaberi.forkcast.recommendation;

import java.math.BigDecimal;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import com.asthaberi.forkcast.menu.MenuItem;
import com.asthaberi.forkcast.order.DeliveryAddress;
import com.asthaberi.forkcast.order.Order;
import com.asthaberi.forkcast.order.OrderActor;
import com.asthaberi.forkcast.order.OrderLine;
import com.asthaberi.forkcast.order.OrderRepository;
import com.asthaberi.forkcast.order.OrderStatus;

/** Creates delivered order histories directly in MongoDB for recommendation tests. */
final class RecommendationTestSupport {

    // Gwalior city centre; a 20 km radius covers all 12 seeded restaurants
    static final double LAT = 26.2124;
    static final double LNG = 78.1772;

    private RecommendationTestSupport() {
    }

    static RecommendationQuery everywhere(int limit) {
        return new RecommendationQuery(LAT, LNG, 20.0, false, limit);
    }

    /** One delivered order of one dish, placed {@code daysAgo} days ago. */
    static Order deliveredOrder(OrderRepository orders, String userId, MenuItem dish, int daysAgo) {
        Instant placedAt = Instant.now().minus(Duration.ofDays(daysAgo));
        Order order = Order.builder().userId(userId).idempotencyKey(UUID.randomUUID().toString())
                .restaurantId(dish.getRestaurantId()).restaurantName("Seeded")
                .lines(List.of(new OrderLine(dish.getId(), dish.getName(), dish.getPrice(), 1, dish.getPrice())))
                .subtotal(dish.getPrice()).deliveryFee(BigDecimal.ZERO).tax(BigDecimal.ZERO).total(dish.getPrice())
                .deliveryAddress(new DeliveryAddress("Home", LAT, LNG)).placedAt(placedAt).build();
        for (OrderStatus next : List.of(OrderStatus.ACCEPTED, OrderStatus.PREPARING, OrderStatus.OUT_FOR_DELIVERY,
                OrderStatus.DELIVERED)) {
            order.transitionTo(next, OrderActor.RESTAURANT, placedAt);
        }
        return orders.save(order);
    }

    static MenuItem dish(Map<String, MenuItem> byName, String name) {
        MenuItem dish = byName.get(name);
        if (dish == null) {
            throw new IllegalArgumentException("No seeded dish named " + name);
        }
        return dish;
    }
}
