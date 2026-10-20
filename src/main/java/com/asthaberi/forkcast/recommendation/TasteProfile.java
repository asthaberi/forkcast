package com.asthaberi.forkcast.recommendation;

import java.time.Duration;
import java.time.Instant;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import com.asthaberi.forkcast.order.Order;
import com.asthaberi.forkcast.order.OrderLine;

/**
 * What a customer likes, learned from their order history and their own reviews.
 *
 * Each ordered dish gets a weight that adds up its evidence:
 * <ul>
 *   <li><b>quantity</b>, capped at 3 so one family-size order doesn't outweigh a habit;</li>
 *   <li><b>recency</b>: an order's weight halves every 30 days, so tastes can change;</li>
 *   <li><b>the review of that order</b>: 5 stars x1.5, 4 x1.2, 3 x0.8, no review x1.0, and 1-2 stars count
 *       <i>against</i> the dish.</li>
 * </ul>
 * Dishes whose total is positive are liked; the rest are disliked and never recommended.
 *
 * @param liked          liked dishes by menu item id
 * @param ordered        every dish ever ordered (excluded from "for you", which is about new dishes)
 * @param disliked       dishes whose reviews outweigh their orders
 * @param vegetarianOnly every dish in the history is vegetarian (with at least 5 different dishes to go on, so a
 *                       few vegetarian orders by chance don't hide everything else), so non-vegetarian
 *                       dishes aren't recommended
 */
record TasteProfile(Map<String, DishTaste> liked, Set<String> ordered, Set<String> disliked, boolean vegetarianOnly) {

    static final Duration HALF_LIFE = Duration.ofDays(30);
    static final int MAX_QUANTITY_COUNTED = 3;
    static final int MIN_DISHES_FOR_VEGETARIAN_ONLY = 5;

    static TasteProfile empty() {
        return new TasteProfile(Map.of(), Set.of(), Set.of(), false);
    }

    boolean isEmpty() {
        return liked.isEmpty();
    }

    /** Liked dishes, strongest first. */
    List<DishTaste> strongest(int limit) {
        return liked.values().stream()
                .sorted(Comparator.comparingDouble(DishTaste::weight).reversed())
                .limit(limit)
                .toList();
    }

    /**
     * @param orders         the customer's orders (cancelled and rejected ones already left out)
     * @param starsByOrderId the customer's review rating per order id
     * @param vegetarianById whether each ordered dish is vegetarian, for dishes still on the menu
     */
    static TasteProfile from(List<Order> orders, Map<String, Integer> starsByOrderId,
                             Map<String, Boolean> vegetarianById, Instant now) {
        Map<String, Double> weights = new HashMap<>();
        Map<String, DishTaste> stats = new HashMap<>();
        for (Order order : orders) {
            double orderWeight = recency(order.getPlacedAt(), now) * reviewFactor(starsByOrderId.get(order.getId()));
            for (OrderLine line : order.getLines()) {
                weights.merge(line.menuItemId(), Math.min(line.quantity(), MAX_QUANTITY_COUNTED) * orderWeight,
                        Double::sum);
                stats.merge(line.menuItemId(), new DishTaste(line.menuItemId(), line.name(), 0, 1, order.getPlacedAt()),
                        (a, b) -> new DishTaste(a.menuItemId(), a.name(), 0, a.timesOrdered() + 1,
                                a.lastOrderedAt().isAfter(b.lastOrderedAt()) ? a.lastOrderedAt() : b.lastOrderedAt()));
            }
        }

        Map<String, DishTaste> liked = new HashMap<>();
        Set<String> disliked = new HashSet<>();
        weights.forEach((id, weight) -> {
            if (weight > 0) {
                DishTaste dish = stats.get(id);
                liked.put(id, new DishTaste(id, dish.name(), weight, dish.timesOrdered(), dish.lastOrderedAt()));
            } else {
                disliked.add(id);
            }
        });

        List<Boolean> known = weights.keySet().stream().filter(vegetarianById::containsKey).map(vegetarianById::get).toList();
        boolean vegetarianOnly = known.size() >= MIN_DISHES_FOR_VEGETARIAN_ONLY && known.stream().allMatch(Boolean::booleanValue);
        return new TasteProfile(liked, Set.copyOf(weights.keySet()), disliked, vegetarianOnly);
    }

    private static double recency(Instant placedAt, Instant now) {
        double ageDays = Math.max(0, Duration.between(placedAt, now).toHours() / 24.0);
        return Math.pow(0.5, ageDays / HALF_LIFE.toDays());
    }

    static double reviewFactor(Integer stars) {
        if (stars == null) {
            return 1.0;
        }
        return switch (stars) {
            case 5 -> 1.5;
            case 4 -> 1.2;
            case 3 -> 0.8;
            default -> -1.0;
        };
    }

    /** @param weight the strength of the preference (0 for the intermediate merge values) */
    record DishTaste(String menuItemId, String name, double weight, int timesOrdered, Instant lastOrderedAt) {
    }
}
