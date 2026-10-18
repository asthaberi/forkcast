package com.asthaberi.forkcast.order;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doThrow;

import java.math.BigDecimal;
import java.time.LocalTime;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.Callable;
import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

import com.asthaberi.forkcast.cart.CartRepository;
import com.asthaberi.forkcast.cart.CartService;
import com.asthaberi.forkcast.cart.dto.AddCartItemRequest;
import com.asthaberi.forkcast.common.exception.BusinessException;
import com.asthaberi.forkcast.common.exception.ErrorCode;
import com.asthaberi.forkcast.menu.MenuItem;
import com.asthaberi.forkcast.menu.MenuItemRepository;
import com.asthaberi.forkcast.menu.SpiceLevel;
import com.asthaberi.forkcast.order.OrderService.OrderPlacement;
import com.asthaberi.forkcast.order.dto.PlaceOrderRequest;
import com.asthaberi.forkcast.restaurant.Restaurant;
import com.asthaberi.forkcast.restaurant.RestaurantRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.data.mongodb.core.geo.GeoJsonPoint;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.mongodb.MongoDBContainer;

/**
 * Order placement against a real MongoDB (a single-node replica set started by Testcontainers), so
 * transactions, unique indexes and optimistic locking behave exactly as in production. Mocks can't
 * prove any of those. Skipped when Docker isn't available.
 */
@Testcontainers(disabledWithoutDocker = true)
@SpringBootTest(properties = {"forkcast.seed.enabled=false", "spring.cache.type=none"})
class OrderPlacementIntegrationTest {

    @Container
    @ServiceConnection
    static MongoDBContainer mongo = new MongoDBContainer("mongo:8.0").withReplicaSet();

    private static final String USER = "integration-user";
    private static final PlaceOrderRequest TO_HOSTEL =
            new PlaceOrderRequest(new DeliveryAddress("Hostel 3", 26.2120, 78.1830));

    @Autowired
    private OrderService orderService;
    @MockitoSpyBean
    private CartService cartService;
    @Autowired
    private OrderRepository orderRepository;
    @Autowired
    private CartRepository cartRepository;
    @Autowired
    private RestaurantRepository restaurantRepository;
    @Autowired
    private MenuItemRepository menuItemRepository;

    private MenuItem dosa;

    @BeforeEach
    void setUp() {
        orderRepository.deleteAll();
        cartRepository.deleteAll();
        menuItemRepository.deleteAll();
        restaurantRepository.deleteAll();

        // Same opening and closing time = open 24 hours, so the test doesn't depend on the time of day
        Restaurant restaurant = restaurantRepository.save(Restaurant.builder().name("Dosa Junction")
                .address("Patel Nagar").city("Gwalior").location(new GeoJsonPoint(78.1850, 26.2080))
                .cuisines(List.of("south indian")).opensAt(LocalTime.MIDNIGHT).closesAt(LocalTime.MIDNIGHT)
                .rating(4.2).build());
        dosa = menuItemRepository.save(MenuItem.builder().restaurantId(restaurant.getId()).name("Masala Dosa")
                .category("Dosas").price(new BigDecimal("129.00")).vegetarian(true).spiceLevel(SpiceLevel.MILD)
                .tags(List.of()).available(true).build());
        cartService.addItem(USER, new AddCartItemRequest(dosa.getId(), 2), false);
    }

    @Test
    void placesOrderAndEmptiesCartWithPricesFrozen() {
        OrderPlacement placement = orderService.placeOrder(USER, "key-1", TO_HOSTEL);

        assertThat(placement.created()).isTrue();
        assertThat(cartService.getCart(USER).items()).isEmpty();

        dosa.setPrice(new BigDecimal("199.00"));
        menuItemRepository.save(dosa);
        Order stored = orderRepository.findById(placement.order().id()).orElseThrow();
        assertThat(stored.getLines().getFirst().unitPrice()).isEqualByComparingTo("129.00");
        assertThat(stored.getTotal()).isEqualByComparingTo("300.90");
    }

    @Test
    void sameKeyTwiceReturnsTheSameOrder() {
        OrderPlacement first = orderService.placeOrder(USER, "key-1", TO_HOSTEL);
        OrderPlacement second = orderService.placeOrder(USER, "key-1", TO_HOSTEL);

        assertThat(second.created()).isFalse();
        assertThat(second.order().id()).isEqualTo(first.order().id());
        assertThat(orderRepository.count()).isEqualTo(1);
    }

    @Test
    void concurrentRequestsWithTheSameKeyCreateExactlyOneOrder() throws Exception {
        List<Outcome> outcomes = runConcurrently(10, attempt -> () -> orderService.placeOrder(USER, "double-tap", TO_HOSTEL));

        assertThat(orderRepository.count()).isEqualTo(1);
        assertThat(outcomes).allSatisfy(outcome -> assertThat(outcome.error()).isNull());
        assertThat(outcomes).filteredOn(outcome -> outcome.placement().created()).hasSize(1);
        assertThat(outcomes).extracting(outcome -> outcome.placement().order().id()).containsOnly(
                orderRepository.findAll().getFirst().getId());
    }

    @Test
    void concurrentCheckoutsWithDifferentKeysStillCreateOnlyOneOrderFromOneCart() throws Exception {
        // E.g. phone and laptop placing the same cart at the same moment
        List<Outcome> outcomes = runConcurrently(5, attempt -> () -> orderService.placeOrder(USER, "device-" + attempt, TO_HOSTEL));

        assertThat(orderRepository.count()).isEqualTo(1);
        assertThat(outcomes).filteredOn(outcome -> outcome.error() == null).hasSize(1);
        assertThat(outcomes).filteredOn(outcome -> outcome.error() != null)
                .allSatisfy(outcome -> assertThat(outcome.error().code())
                        .isIn(ErrorCode.CART_EMPTY, ErrorCode.CONCURRENT_MODIFICATION));
    }

    @Test
    void failureAfterTheOrderInsertRollsTheWholeCheckoutBack() {
        doThrow(new IllegalStateException("simulated crash")).when(cartService).clearAfterCheckout(any());

        assertThatThrownBy(() -> orderService.placeOrder(USER, "key-1", TO_HOSTEL))
                .hasMessage("simulated crash");

        // The order was inserted inside the transaction, then rolled back: no order, cart untouched
        assertThat(orderRepository.count()).isZero();
        assertThat(cartService.getCart(USER).itemCount()).isEqualTo(2);
    }

    private List<Outcome> runConcurrently(int threads, java.util.function.IntFunction<Callable<OrderPlacement>> task)
            throws Exception {
        CyclicBarrier startTogether = new CyclicBarrier(threads);
        try (ExecutorService pool = Executors.newFixedThreadPool(threads)) {
            List<Future<Outcome>> futures = new ArrayList<>();
            for (int i = 0; i < threads; i++) {
                Callable<OrderPlacement> call = task.apply(i);
                futures.add(pool.submit(() -> {
                    startTogether.await();
                    try {
                        return new Outcome(call.call(), null);
                    } catch (BusinessException e) {
                        return new Outcome(null, e);
                    }
                }));
            }
            List<Outcome> outcomes = new ArrayList<>();
            for (Future<Outcome> future : futures) {
                outcomes.add(future.get());
            }
            return outcomes;
        }
    }

    private record Outcome(OrderPlacement placement, BusinessException error) {
    }
}
