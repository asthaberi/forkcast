package com.asthaberi.forkcast.order;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.math.BigDecimal;
import java.time.Clock;
import java.time.LocalTime;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.util.List;
import java.util.Optional;

import com.asthaberi.forkcast.cart.Cart;
import com.asthaberi.forkcast.cart.CartService;
import com.asthaberi.forkcast.cart.CheckoutCart;
import com.asthaberi.forkcast.cart.dto.CartResponse;
import com.asthaberi.forkcast.cart.dto.CartResponse.CartLineResponse;
import com.asthaberi.forkcast.common.exception.BusinessException;
import com.asthaberi.forkcast.common.exception.ErrorCode;
import com.asthaberi.forkcast.order.OrderService.OrderPlacement;
import com.asthaberi.forkcast.order.dto.PlaceOrderRequest;
import com.asthaberi.forkcast.order.events.OrderEventBus;
import com.asthaberi.forkcast.restaurant.RestaurantService;
import com.asthaberi.forkcast.restaurant.SearchProperties;
import com.asthaberi.forkcast.restaurant.dto.RestaurantResponse;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import com.mongodb.MongoCommandException;
import com.mongodb.ServerAddress;
import org.bson.BsonDocument;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.dao.InvalidDataAccessApiUsageException;
import org.springframework.transaction.PlatformTransactionManager;

@ExtendWith(MockitoExtension.class)
class OrderServiceTest {

    private static final ZoneId IST = ZoneId.of("Asia/Kolkata");
    // Restaurant at (26.2080, 78.1850); this address is ~0.5 km away
    private static final PlaceOrderRequest NEARBY = new PlaceOrderRequest(new DeliveryAddress("Home", 26.2120, 78.1830));

    @Mock
    private OrderRepository orderRepository;
    @Mock
    private CartService cartService;
    @Mock
    private RestaurantService restaurantService;
    @Mock
    private PlatformTransactionManager transactionManager;
    @Mock
    private OrderEventBus eventBus;

    private OrderService orderService;

    @BeforeEach
    void setUp() {
        // 11:00 IST: off-peak, so the delivery radius is 5 km
        Clock clock = Clock.fixed(ZonedDateTime.of(2026, 10, 18, 11, 0, 0, 0, IST).toInstant(), IST);
        SearchProperties search = new SearchProperties(5.0, 3.0, List.of(new SearchProperties.PeakWindow(8, 10)), 7);
        orderService = new OrderService(orderRepository, cartService, restaurantService, search, clock, eventBus,
                transactionManager);
    }

    @Test
    void placesOrderWithCartPricesAndClearsTheCart() {
        Cart cart = Cart.emptyFor("u1");
        givenCart(cart, line("dosa", "Masala Dosa", true));
        givenRestaurant(true);
        when(orderRepository.insert(any(Order.class))).thenAnswer(invocation -> invocation.getArgument(0));

        OrderPlacement placement = orderService.placeOrder("u1", "key-1", NEARBY);

        assertThat(placement.created()).isTrue();
        assertThat(placement.order().items()).singleElement().satisfies(line -> {
            assertThat(line.unitPrice()).isEqualTo(new BigDecimal("129.00"));
            assertThat(line.quantity()).isEqualTo(2);
        });
        assertThat(placement.order().total()).isEqualTo(new BigDecimal("300.90"));
        verify(cartService).clearAfterCheckout(cart);
        verify(eventBus).publish(any());
    }

    @Test
    void repeatedKeyReturnsTheExistingOrderWithoutPlacingAgain() {
        Order existing = Order.builder().userId("u1").idempotencyKey("key-1").restaurantId("r1")
                .restaurantName("Dosa Junction").lines(List.of()).subtotal(BigDecimal.ONE).deliveryFee(BigDecimal.ZERO)
                .tax(BigDecimal.ZERO).total(BigDecimal.ONE).deliveryAddress(NEARBY.deliveryAddress())
                .placedAt(java.time.Instant.now()).build();
        when(orderRepository.findByUserIdAndIdempotencyKey("u1", "key-1")).thenReturn(Optional.of(existing));

        OrderPlacement placement = orderService.placeOrder("u1", "key-1", NEARBY);

        assertThat(placement.created()).isFalse();
        verify(cartService, never()).loadForCheckout(any());
        verify(eventBus, never()).publish(any());
    }

    @Test
    void afterLosingARaceReturnsTheOrderTheWinnerCreated() {
        givenCart(Cart.emptyFor("u1"), line("dosa", "Masala Dosa", true));
        givenRestaurant(true);
        Order winner = Order.builder().userId("u1").idempotencyKey("key-1").restaurantId("r1")
                .restaurantName("Dosa Junction").lines(List.of()).subtotal(BigDecimal.ONE).deliveryFee(BigDecimal.ZERO)
                .tax(BigDecimal.ZERO).total(BigDecimal.ONE).deliveryAddress(NEARBY.deliveryAddress())
                .placedAt(java.time.Instant.now()).build();
        when(orderRepository.findByUserIdAndIdempotencyKey("u1", "key-1"))
                .thenReturn(Optional.empty())
                .thenReturn(Optional.of(winner));
        when(orderRepository.insert(any(Order.class))).thenThrow(new DuplicateKeyException("same key"));

        OrderPlacement placement = orderService.placeOrder("u1", "key-1", NEARBY);

        assertThat(placement.created()).isFalse();
        verify(orderRepository, times(1)).insert(any(Order.class));
    }

    @Test
    void retriesMongoWriteConflictsLabelledTransient() {
        givenCart(Cart.emptyFor("u1"), line("dosa", "Masala Dosa", true));
        givenRestaurant(true);
        // What MongoDB returns when two transactions write the same document; Spring wraps it as a
        // DataIntegrityViolationException, but the driver exception keeps the TransientTransactionError label
        MongoCommandException writeConflict = new MongoCommandException(BsonDocument.parse(
                "{ok: 0, code: 112, codeName: 'WriteConflict', errmsg: 'conflict', errorLabels: ['TransientTransactionError']}"),
                new ServerAddress());
        when(orderRepository.insert(any(Order.class)))
                .thenThrow(new DataIntegrityViolationException("WriteConflict", writeConflict))
                .thenAnswer(invocation -> invocation.getArgument(0));

        OrderPlacement placement = orderService.placeOrder("u1", "key-1", NEARBY);

        assertThat(placement.created()).isTrue();
        verify(orderRepository, times(2)).insert(any(Order.class));
    }

    @Test
    void nonConcurrencyDatabaseErrorsAreNotRetriedOrDisguised() {
        givenCart(Cart.emptyFor("u1"), line("dosa", "Masala Dosa", true));
        givenRestaurant(true);
        when(orderRepository.insert(any(Order.class)))
                .thenThrow(new InvalidDataAccessApiUsageException("Transactions need a replica set"));

        assertThatThrownBy(() -> orderService.placeOrder("u1", "key-1", NEARBY))
                .isInstanceOf(InvalidDataAccessApiUsageException.class);
        verify(orderRepository, times(1)).insert(any(Order.class));
    }

    @Test
    void rejectsEmptyCart() {
        when(cartService.loadForCheckout("u1"))
                .thenReturn(new CheckoutCart(Cart.emptyFor("u1"), CartResponse.empty()));

        assertCode(() -> orderService.placeOrder("u1", "k", NEARBY), ErrorCode.CART_EMPTY);
    }

    @Test
    void rejectsCartWithUnavailableItemsAndNamesThem() {
        givenCart(Cart.emptyFor("u1"), line("dosa", "Masala Dosa", true), line("vada", "Medu Vada", false));

        assertThatThrownBy(() -> orderService.placeOrder("u1", "k", NEARBY))
                .isInstanceOfSatisfying(BusinessException.class, ex -> {
                    assertThat(ex.code()).isEqualTo(ErrorCode.CART_HAS_UNAVAILABLE_ITEMS);
                    assertThat(ex.details().get("unavailableItems")).isEqualTo(List.of("Medu Vada"));
                });
        verify(orderRepository, never()).insert(any(Order.class));
    }

    @Test
    void rejectsClosedRestaurant() {
        givenCart(Cart.emptyFor("u1"), line("dosa", "Masala Dosa", true));
        givenRestaurant(false);

        assertCode(() -> orderService.placeOrder("u1", "k", NEARBY), ErrorCode.RESTAURANT_CLOSED);
    }

    @Test
    void rejectsAddressBeyondServingRadius() {
        givenCart(Cart.emptyFor("u1"), line("dosa", "Masala Dosa", true));
        givenRestaurant(true);
        // ~8 km away
        PlaceOrderRequest far = new PlaceOrderRequest(new DeliveryAddress("Airport", 26.2700, 78.2300));

        assertThatThrownBy(() -> orderService.placeOrder("u1", "k", far))
                .isInstanceOfSatisfying(BusinessException.class, ex -> {
                    assertThat(ex.code()).isEqualTo(ErrorCode.OUT_OF_DELIVERY_RANGE);
                    assertThat(ex.details()).containsEntry("maxDistanceKm", 5.0);
                });
    }

    private void givenCart(Cart cart, CartLineResponse... lines) {
        List<CartLineResponse> items = List.of(lines);
        BigDecimal subtotal = items.stream().map(CartLineResponse::lineTotal).reduce(BigDecimal.ZERO, BigDecimal::add);
        CartResponse priced = new CartResponse("r1", "Dosa Junction", items, 2, subtotal,
                new BigDecimal("30.00"), new BigDecimal("12.90"), subtotal.add(new BigDecimal("42.90")));
        when(cartService.loadForCheckout("u1")).thenReturn(new CheckoutCart(cart, priced));
    }

    private void givenRestaurant(boolean open) {
        when(restaurantService.getById("r1")).thenReturn(new RestaurantResponse("r1", "Dosa Junction", "Patel Nagar",
                "Gwalior", 26.2080, 78.1850, List.of("south indian"), 4.2, 0, LocalTime.of(7, 0), LocalTime.of(22, 0),
                open, null, null));
    }

    private static CartLineResponse line(String id, String name, boolean available) {
        return new CartLineResponse(id, name, new BigDecimal("129.00"), 2,
                available ? new BigDecimal("258.00") : new BigDecimal("0.00"), available);
    }

    private static void assertCode(org.assertj.core.api.ThrowableAssert.ThrowingCallable call, ErrorCode code) {
        assertThatThrownBy(call).isInstanceOfSatisfying(BusinessException.class,
                ex -> assertThat(ex.code()).isEqualTo(code));
    }
}
