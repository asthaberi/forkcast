package com.asthaberi.forkcast.order;

import java.io.IOException;
import java.time.Clock;
import java.util.Optional;
import java.util.Set;
import java.util.function.Supplier;

import com.asthaberi.forkcast.common.exception.BusinessException;
import com.asthaberi.forkcast.common.exception.ErrorCode;
import com.asthaberi.forkcast.common.exception.ResourceNotFoundException;
import com.asthaberi.forkcast.common.web.PageResponse;
import com.asthaberi.forkcast.order.dto.OrderResponse;
import com.asthaberi.forkcast.order.events.OrderEvent;
import com.asthaberi.forkcast.order.events.OrderEventBus;
import com.asthaberi.forkcast.order.events.OrderEventStreams;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.dao.OptimisticLockingFailureException;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

/** Everything that happens to an order after it's placed: status changes and live tracking. */
@Slf4j
@Service
@RequiredArgsConstructor
public class OrderLifecycleService {

    static final int MAX_ATTEMPTS = 3;

    private final OrderRepository orderRepository;
    private final OrderEventBus eventBus;
    private final OrderEventStreams streams;
    private final Clock clock;

    public OrderResponse cancelByCustomer(String userId, String orderId) {
        return transition(() -> orderRepository.findByIdAndUserId(orderId, userId), orderId,
                OrderStatus.CANCELLED, OrderActor.CUSTOMER);
    }

    public OrderResponse updateByRestaurant(String restaurantId, String orderId, OrderStatus target) {
        return transition(() -> orderRepository.findByIdAndRestaurantId(orderId, restaurantId), orderId,
                target, OrderActor.RESTAURANT);
    }

    /** @param statuses empty means all statuses */
    public PageResponse<OrderResponse> restaurantOrders(String restaurantId, Set<OrderStatus> statuses, int page,
                                                        int size) {
        PageRequest pageRequest = PageRequest.of(page, size);
        var orders = statuses.isEmpty()
                ? orderRepository.findByRestaurantIdOrderByPlacedAtDesc(restaurantId, pageRequest)
                : orderRepository.findByRestaurantIdAndStatusInOrderByPlacedAtDesc(restaurantId, statuses, pageRequest);
        return PageResponse.from(orders, OrderMapper::toResponse);
    }

    /**
     * Live status stream for one of the customer's own orders. Subscribes first and then sends the current
     * status, so no change can slip through unseen in between. If a change does arrive first, the client
     * keeps whichever event has the higher sequence. The stream ends once the order is delivered,
     * cancelled or rejected.
     */
    public SseEmitter trackOrder(String userId, String orderId) {
        findOwnOrder(userId, orderId);
        SseEmitter emitter = streams.subscribeToOrder(orderId);
        Order current = findOwnOrder(userId, orderId);
        try {
            OrderEventStreams.send(emitter, OrderEvent.of(current));
            if (current.getStatus().isTerminal()) {
                emitter.complete();
            }
        } catch (IOException e) {
            emitter.completeWithError(e);
        }
        return emitter;
    }

    /** Live feed of new orders and status changes for a restaurant's dashboard. */
    public SseEmitter followRestaurant(String restaurantId) {
        SseEmitter emitter = streams.subscribeToRestaurant(restaurantId);
        try {
            // Flushes the response headers right away so the client knows it's connected
            emitter.send(SseEmitter.event().comment("connected"));
        } catch (IOException e) {
            emitter.completeWithError(e);
        }
        return emitter;
    }

    /**
     * Optimistic locking makes concurrent changes safe: if the customer cancels while the restaurant accepts,
     * one save wins and the other fails its version check. The loser re-reads the order and re-applies its
     * change, so the state machine judges it against the winner's result (e.g. "can't cancel: ACCEPTED").
     */
    private OrderResponse transition(Supplier<Optional<Order>> loader, String orderId, OrderStatus target,
                                     OrderActor actor) {
        for (int attempt = 1; ; attempt++) {
            Order order = loader.get().orElseThrow(() -> new ResourceNotFoundException("Order", orderId));
            order.transitionTo(target, actor, clock.instant());
            try {
                Order saved = orderRepository.save(order);
                log.info("Order {} -> {} by {}", orderId, target, actor);
                // Published only after the save succeeded, so listeners never see a change that didn't happen
                eventBus.publish(OrderEvent.of(saved));
                return OrderMapper.toResponse(saved);
            } catch (OptimisticLockingFailureException e) {
                if (attempt == MAX_ATTEMPTS) {
                    throw new BusinessException(ErrorCode.CONCURRENT_MODIFICATION,
                            "The order was updated at the same time. Please refresh and try again.");
                }
            }
        }
    }

    private Order findOwnOrder(String userId, String orderId) {
        return orderRepository.findByIdAndUserId(orderId, userId)
                .orElseThrow(() -> new ResourceNotFoundException("Order", orderId));
    }
}
