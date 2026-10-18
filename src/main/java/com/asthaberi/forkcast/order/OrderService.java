package com.asthaberi.forkcast.order;

import java.time.Clock;
import java.time.LocalTime;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ThreadLocalRandom;

import com.asthaberi.forkcast.cart.CartService;
import com.asthaberi.forkcast.cart.CheckoutCart;
import com.asthaberi.forkcast.cart.dto.CartResponse;
import com.asthaberi.forkcast.cart.dto.CartResponse.CartLineResponse;
import com.asthaberi.forkcast.common.exception.BusinessException;
import com.asthaberi.forkcast.common.exception.ErrorCode;
import com.asthaberi.forkcast.common.exception.ResourceNotFoundException;
import com.asthaberi.forkcast.common.geo.GeoUtils;
import com.asthaberi.forkcast.common.web.PageResponse;
import com.asthaberi.forkcast.order.dto.OrderResponse;
import com.asthaberi.forkcast.order.dto.OrderSummary;
import com.asthaberi.forkcast.order.dto.PlaceOrderRequest;
import com.asthaberi.forkcast.restaurant.RestaurantService;
import com.asthaberi.forkcast.restaurant.SearchProperties;
import com.asthaberi.forkcast.restaurant.dto.RestaurantResponse;
import com.mongodb.MongoException;
import lombok.extern.slf4j.Slf4j;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.dao.OptimisticLockingFailureException;
import org.springframework.dao.TransientDataAccessException;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionSystemException;
import org.springframework.transaction.support.TransactionTemplate;

@Slf4j
@Service
public class OrderService {

    static final int MAX_ATTEMPTS = 3;

    private final OrderRepository orderRepository;
    private final CartService cartService;
    private final RestaurantService restaurantService;
    private final SearchProperties searchProperties;
    private final Clock clock;
    private final TransactionTemplate transactionTemplate;

    public OrderService(OrderRepository orderRepository, CartService cartService, RestaurantService restaurantService,
                        SearchProperties searchProperties, Clock clock, PlatformTransactionManager transactionManager) {
        this.orderRepository = orderRepository;
        this.cartService = cartService;
        this.restaurantService = restaurantService;
        this.searchProperties = searchProperties;
        this.clock = clock;
        this.transactionTemplate = new TransactionTemplate(transactionManager);
    }

    /**
     * Turns the user's cart into an order.
     *
     * Idempotent: the client sends a unique Idempotency-Key per checkout attempt, and repeating the request
     * (double tap, network retry) returns the order already created for that key instead of a second one.
     *
     * Atomic: the order insert and the cart clear run in one MongoDB transaction, so there's never an order
     * whose cart wasn't emptied, or an emptied cart without an order.
     */
    public OrderPlacement placeOrder(String userId, String idempotencyKey, PlaceOrderRequest request) {
        for (int attempt = 1; ; attempt++) {
            Optional<Order> existing = orderRepository.findByUserIdAndIdempotencyKey(userId, idempotencyKey);
            if (existing.isPresent()) {
                return new OrderPlacement(OrderMapper.toResponse(existing.get()), false);
            }
            try {
                Order order = transactionTemplate.execute(status -> placeInTransaction(userId, idempotencyKey, request));
                log.info("Order {} placed by user {} for {}", order.getId(), userId, order.getTotal());
                return new OrderPlacement(OrderMapper.toResponse(order), true);
            } catch (RuntimeException e) {
                if (!isConcurrencyConflict(e)) {
                    throw e;
                }
                // Each retry re-runs the idempotency check first, so if a competing request (or even our own
                // commit with an unknown outcome) already created the order, it is returned, never duplicated.
                log.info("Order placement attempt {} for user {} rolled back: {}", attempt, userId, e.toString());
                if (attempt == MAX_ATTEMPTS) {
                    throw new BusinessException(ErrorCode.CONCURRENT_MODIFICATION,
                            "Your cart changed while the order was being placed. Please review it and try again.");
                }
                backOff(attempt);
            }
        }
    }

    private Order placeInTransaction(String userId, String idempotencyKey, PlaceOrderRequest request) {
        CheckoutCart checkout = cartService.loadForCheckout(userId);
        CartResponse cart = checkout.priced();
        validateCart(cart);
        RestaurantResponse restaurant = restaurantService.getById(cart.restaurantId());
        validateRestaurant(restaurant, request.deliveryAddress());

        Order order = orderRepository.insert(Order.builder()
                .userId(userId)
                .idempotencyKey(idempotencyKey)
                .restaurantId(restaurant.id())
                .restaurantName(restaurant.name())
                .lines(OrderMapper.toLines(cart))
                .subtotal(cart.subtotal())
                .deliveryFee(cart.deliveryFee())
                .tax(cart.tax())
                .total(cart.total())
                .deliveryAddress(request.deliveryAddress())
                .placedAt(clock.instant())
                .build());
        cartService.clearAfterCheckout(checkout.cart());
        return order;
    }

    public OrderResponse getOrder(String userId, String orderId) {
        return OrderMapper.toResponse(findOwnOrder(userId, orderId));
    }

    public PageResponse<OrderSummary> listOrders(String userId, int page, int size) {
        return PageResponse.from(orderRepository.findByUserIdOrderByPlacedAtDesc(userId, PageRequest.of(page, size)),
                OrderMapper::toSummary);
    }

    public OrderResponse cancel(String userId, String orderId) {
        Order order = findOwnOrder(userId, orderId);
        order.cancel(clock.instant());
        try {
            return OrderMapper.toResponse(orderRepository.save(order));
        } catch (OptimisticLockingFailureException e) {
            throw new BusinessException(ErrorCode.CONCURRENT_MODIFICATION,
                    "The order was updated at the same time. Please refresh and try again.");
        }
    }

    // Another user's order id returns 404, not 403, so ids of other people's orders aren't confirmed to exist.
    private Order findOwnOrder(String userId, String orderId) {
        return orderRepository.findByIdAndUserId(orderId, userId)
                .orElseThrow(() -> new ResourceNotFoundException("Order", orderId));
    }

    private static void validateCart(CartResponse cart) {
        if (cart.items().isEmpty()) {
            throw new BusinessException(ErrorCode.CART_EMPTY, "Add items to your cart before placing an order");
        }
        List<String> unavailable = cart.items().stream()
                .filter(line -> !line.available())
                .map(CartLineResponse::name)
                .toList();
        if (!unavailable.isEmpty()) {
            throw new BusinessException(ErrorCode.CART_HAS_UNAVAILABLE_ITEMS,
                    "Remove unavailable items before ordering: " + String.join(", ", unavailable),
                    Map.of("unavailableItems", unavailable));
        }
    }

    private void validateRestaurant(RestaurantResponse restaurant, DeliveryAddress address) {
        if (!restaurant.openNow()) {
            throw new BusinessException(ErrorCode.RESTAURANT_CLOSED,
                    restaurant.name() + " is closed right now. It opens at " + restaurant.opensAt() + ".",
                    Map.of("opensAt", restaurant.opensAt().toString()));
        }
        double maxKm = searchProperties.defaultRadiusAt(LocalTime.now(clock));
        double distanceKm = GeoUtils.distanceKm(restaurant.latitude(), restaurant.longitude(),
                address.latitude(), address.longitude());
        if (distanceKm > maxKm) {
            throw new BusinessException(ErrorCode.OUT_OF_DELIVERY_RANGE,
                    "%s delivers within %.1f km right now; this address is %.1f km away"
                            .formatted(restaurant.name(), maxKm, distanceKm),
                    Map.of("distanceKm", Math.round(distanceKm * 100) / 100.0, "maxDistanceKm", maxKm));
        }
    }

    /**
     * Only concurrency conflicts are retried; any other error is a real failure and propagates unchanged.
     * A conflict is: another request inserted an order with the same key first (duplicate key), the cart changed
     * since it was read (OptimisticLockingFailureException, a TransientDataAccessException), the commit outcome
     * is unknown, or MongoDB aborted the transaction with the TransientTransactionError label, its documented
     * signal to retry the whole transaction. Spring translates the most common of those, WriteConflict, into
     * a non-transient DataIntegrityViolationException, so the driver's label is checked directly.
     */
    private static boolean isConcurrencyConflict(RuntimeException e) {
        if (e instanceof DuplicateKeyException || e instanceof TransientDataAccessException
                || e instanceof TransactionSystemException) {
            return true;
        }
        for (Throwable cause = e; cause != null; cause = cause.getCause()) {
            if (cause instanceof MongoException mongo
                    && mongo.hasErrorLabel(MongoException.TRANSIENT_TRANSACTION_ERROR_LABEL)) {
                return true;
            }
        }
        return false;
    }

    private static void backOff(int attempt) {
        try {
            Thread.sleep(ThreadLocalRandom.current().nextLong(10, 30) * attempt);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("Interrupted while retrying order placement", e);
        }
    }

    /** @param created false when an earlier request with the same idempotency key already created the order */
    public record OrderPlacement(OrderResponse order, boolean created) {
    }
}
