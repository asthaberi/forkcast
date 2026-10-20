package com.asthaberi.forkcast.recommendation;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.within;

import java.math.BigDecimal;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;

import com.asthaberi.forkcast.order.DeliveryAddress;
import com.asthaberi.forkcast.order.Order;
import com.asthaberi.forkcast.order.OrderLine;
import com.asthaberi.forkcast.recommendation.TasteProfile.DishTaste;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

class TasteProfileTest {

    private static final Instant NOW = Instant.parse("2026-10-20T12:00:00Z");

    @Test
    void recentOrdersCountMoreThanOldOnes() {
        TasteProfile taste = TasteProfile.from(List.of(
                        order("o1", 0, line("dosa", 1)),
                        order("o2", 30, line("pizza", 1)),
                        order("o3", 60, line("burger", 1))),
                Map.of(), Map.of(), NOW);

        assertThat(taste.liked().get("dosa").weight()).isCloseTo(1.0, within(1e-9));
        assertThat(taste.liked().get("pizza").weight()).isCloseTo(0.5, within(1e-9)); // one half-life
        assertThat(taste.liked().get("burger").weight()).isCloseTo(0.25, within(1e-9));
        assertThat(taste.strongest(2)).extracting(DishTaste::menuItemId).containsExactly("dosa", "pizza");
    }

    @Test
    void reviewsRaiseOrLowerWhatAnOrderSaysAndBadOnesTurnIntoDislikes() {
        TasteProfile taste = TasteProfile.from(List.of(
                        order("o1", 0, line("dosa", 1)),
                        order("o2", 0, line("vada", 1)),
                        order("o3", 0, line("noodles", 1)),
                        order("o4", 0, line("idli", 1))),
                Map.of("o1", 5, "o2", 3, "o3", 1), Map.of(), NOW);

        assertThat(taste.liked().get("dosa").weight()).isCloseTo(1.5, within(1e-9));
        assertThat(taste.liked().get("vada").weight()).isCloseTo(0.8, within(1e-9));
        assertThat(taste.liked().get("idli").weight()).isCloseTo(1.0, within(1e-9)); // not reviewed
        assertThat(taste.liked()).doesNotContainKey("noodles");
        assertThat(taste.disliked()).containsExactly("noodles");
        assertThat(taste.ordered()).contains("noodles"); // still never recommended as "new"
    }

    @Test
    void aDishOrderedOftenOutweighsOneBadExperience() {
        TasteProfile taste = TasteProfile.from(List.of(
                        order("o1", 1, line("biryani", 1)),
                        order("o2", 2, line("biryani", 1)),
                        order("o3", 3, line("biryani", 1))),
                Map.of("o1", 1), Map.of(), NOW);

        assertThat(taste.liked().get("biryani").timesOrdered()).isEqualTo(3);
        assertThat(taste.disliked()).isEmpty();
    }

    @Test
    void quantityIsCappedSoOneBigOrderIsNotAHabit() {
        TasteProfile taste = TasteProfile.from(List.of(
                        order("o1", 0, line("naan", 12)),
                        order("o2", 0, line("dal", 2))),
                Map.of(), Map.of(), NOW);

        assertThat(taste.liked().get("naan").weight()).isCloseTo(3.0, within(1e-9));
        assertThat(taste.liked().get("dal").weight()).isCloseTo(2.0, within(1e-9));
    }

    @Test
    void vegetarianOnlyNeedsAnAllVegetarianHistoryOfAtLeastFiveDishes() {
        Map<String, Boolean> vegetarian = Map.of("dosa", true, "idli", true, "vada", true, "upma", true,
                "paneer", true, "chicken", false);
        List<Order> fourVeg = List.of(order("o1", 0, line("dosa", 1), line("idli", 1), line("vada", 1),
                line("upma", 1)));
        List<Order> fiveVeg = List.of(order("o1", 0, line("dosa", 1), line("idli", 1), line("vada", 1),
                line("upma", 1), line("paneer", 1)));
        List<Order> fiveVegAndChicken = List.of(order("o1", 0, line("dosa", 1), line("idli", 1), line("vada", 1),
                line("upma", 1), line("paneer", 1), line("chicken", 1)));

        assertThat(TasteProfile.from(fourVeg, Map.of(), vegetarian, NOW).vegetarianOnly()).isFalse();
        assertThat(TasteProfile.from(fiveVeg, Map.of(), vegetarian, NOW).vegetarianOnly()).isTrue();
        assertThat(TasteProfile.from(fiveVegAndChicken, Map.of(), vegetarian, NOW).vegetarianOnly()).isFalse();
    }

    private static OrderLine line(String dishId, int quantity) {
        return new OrderLine(dishId, dishId, BigDecimal.TEN, quantity, BigDecimal.TEN);
    }

    private static Order order(String id, int daysAgo, OrderLine... lines) {
        Order order = Order.builder().userId("u1").idempotencyKey(id).restaurantId("r1").restaurantName("R")
                .lines(List.of(lines)).subtotal(BigDecimal.ZERO).deliveryFee(BigDecimal.ZERO).tax(BigDecimal.ZERO)
                .total(BigDecimal.ZERO).deliveryAddress(new DeliveryAddress("Home", 26.21, 78.18))
                .placedAt(NOW.minus(Duration.ofDays(daysAgo))).build();
        ReflectionTestUtils.setField(order, "id", id);
        return order;
    }
}
