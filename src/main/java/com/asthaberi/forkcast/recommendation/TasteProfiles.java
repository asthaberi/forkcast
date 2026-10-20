package com.asthaberi.forkcast.recommendation;

import java.time.Clock;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

import com.asthaberi.forkcast.menu.MenuItem;
import com.asthaberi.forkcast.menu.MenuItemRepository;
import com.asthaberi.forkcast.order.Order;
import com.asthaberi.forkcast.order.OrderLine;
import com.asthaberi.forkcast.order.OrderRepository;
import com.asthaberi.forkcast.order.OrderStatus;
import com.asthaberi.forkcast.review.Review;
import com.asthaberi.forkcast.review.ReviewRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Component;

/** Builds a customer's TasteProfile from their recent orders and reviews: three indexed queries. */
@Component
@RequiredArgsConstructor
class TasteProfiles {

    // Enough history to know someone's habits; older orders barely count after the 30-day half-life anyway
    static final int RECENT_ORDERS = 50;
    private static final Set<OrderStatus> NOT_EATEN = Set.of(OrderStatus.CANCELLED, OrderStatus.REJECTED);

    private final OrderRepository orderRepository;
    private final ReviewRepository reviewRepository;
    private final MenuItemRepository menuItemRepository;
    private final Clock clock;

    TasteProfile of(String userId) {
        List<Order> orders = orderRepository.findByUserIdOrderByPlacedAtDesc(userId, PageRequest.of(0, RECENT_ORDERS))
                .stream()
                .filter(order -> !NOT_EATEN.contains(order.getStatus()))
                .toList();
        if (orders.isEmpty()) {
            return TasteProfile.empty();
        }
        Map<String, Integer> starsByOrderId = reviewRepository.findByUserId(userId).stream()
                .filter(Review::isVerifiedOrder)
                .collect(Collectors.toMap(Review::getOrderId, Review::getRating, (a, b) -> a));
        Set<String> dishIds = orders.stream().flatMap(order -> order.getLines().stream())
                .map(OrderLine::menuItemId).collect(Collectors.toSet());
        Map<String, Boolean> vegetarianById = menuItemRepository.findAllById(dishIds).stream()
                .collect(Collectors.toMap(MenuItem::getId, MenuItem::isVegetarian));
        return TasteProfile.from(orders, starsByOrderId, vegetarianById, clock.instant());
    }
}
