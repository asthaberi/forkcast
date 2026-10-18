package com.asthaberi.forkcast.cart;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.math.BigDecimal;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Optional;

import com.asthaberi.forkcast.cart.dto.AddCartItemRequest;
import com.asthaberi.forkcast.cart.dto.CartResponse;
import com.asthaberi.forkcast.common.exception.BusinessException;
import com.asthaberi.forkcast.common.exception.ErrorCode;
import com.asthaberi.forkcast.menu.MenuService;
import com.asthaberi.forkcast.menu.SpiceLevel;
import com.asthaberi.forkcast.menu.dto.MenuItemResponse;
import com.asthaberi.forkcast.restaurant.RestaurantService;
import com.asthaberi.forkcast.restaurant.dto.RestaurantResponse;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.dao.OptimisticLockingFailureException;

@ExtendWith(MockitoExtension.class)
class CartServiceTest {

    private static final MenuItemResponse DOSA = new MenuItemResponse("dosa", "r1", "Masala Dosa", null, "Dosas",
            new BigDecimal("129.00"), true, SpiceLevel.MILD, List.of(), true);

    @Mock
    private CartRepository cartRepository;
    @Mock
    private MenuService menuService;
    @Mock
    private RestaurantService restaurantService;
    @Mock
    private CartPricer cartPricer;

    private CartService cartService;

    @BeforeEach
    void setUp() {
        Clock clock = Clock.fixed(Instant.parse("2026-10-18T07:00:00Z"), ZoneOffset.UTC);
        cartService = new CartService(cartRepository, menuService, restaurantService, cartPricer, clock);
    }

    @Test
    void retriesWhenAnotherRequestSavedFirstAndReappliesTheChangeToFreshData() {
        givenAvailableDosa();
        // Each read returns a new object, like a real database read
        when(cartRepository.findByUserId("u1")).thenAnswer(invocation -> Optional.of(Cart.emptyFor("u1")));
        when(cartRepository.save(any()))
                .thenThrow(new OptimisticLockingFailureException("version changed"))
                .thenAnswer(invocation -> invocation.getArgument(0));
        when(cartPricer.price(any())).thenReturn(CartResponse.empty());

        cartService.addItem("u1", new AddCartItemRequest("dosa", 2), false);

        ArgumentCaptor<Cart> saved = ArgumentCaptor.forClass(Cart.class);
        verify(cartRepository, times(2)).save(saved.capture());
        // The successful save has quantity 2, not 4: the change ran again on a fresh read
        assertThat(saved.getAllValues().get(1).getLines().getFirst().quantity()).isEqualTo(2);
    }

    @Test
    void retriesWhenTwoFirstAddsRaceToCreateTheCart() {
        givenAvailableDosa();
        when(cartRepository.findByUserId("u1")).thenAnswer(invocation -> Optional.empty());
        when(cartRepository.save(any()))
                .thenThrow(new DuplicateKeyException("userId exists"))
                .thenAnswer(invocation -> invocation.getArgument(0));
        when(cartPricer.price(any())).thenReturn(CartResponse.empty());

        cartService.addItem("u1", new AddCartItemRequest("dosa", 1), false);

        verify(cartRepository, times(2)).save(any());
    }

    @Test
    void givesUpAfterMaxAttempts() {
        when(cartRepository.findByUserId("u1")).thenAnswer(invocation -> Optional.of(Cart.emptyFor("u1")));
        when(cartRepository.save(any())).thenThrow(new OptimisticLockingFailureException("always"));

        assertThatThrownBy(() -> cartService.clear("u1"))
                .isInstanceOfSatisfying(BusinessException.class,
                        ex -> assertThat(ex.code()).isEqualTo(ErrorCode.CONCURRENT_MODIFICATION));
        verify(cartRepository, times(CartService.MAX_ATTEMPTS)).save(any());
    }

    @Test
    void rejectsUnavailableItemWithoutTouchingTheCart() {
        MenuItemResponse soldOut = new MenuItemResponse("vada", "r1", "Medu Vada", null, "Breakfast",
                new BigDecimal("99.00"), true, SpiceLevel.MILD, List.of(), false);
        when(menuService.getItem("vada")).thenReturn(soldOut);

        assertThatThrownBy(() -> cartService.addItem("u1", new AddCartItemRequest("vada", 1), false))
                .isInstanceOfSatisfying(BusinessException.class,
                        ex -> assertThat(ex.code()).isEqualTo(ErrorCode.ITEM_UNAVAILABLE));
        verify(cartRepository, never()).save(any());
    }

    @Test
    void replaceClearsCartFromAnotherRestaurant() {
        givenAvailableDosa();
        Cart pizzaCart = Cart.emptyFor("u1");
        pizzaCart.addItem("r2", "Pizza Piazza", "pizza", "Margherita", 1);
        when(cartRepository.findByUserId("u1")).thenReturn(Optional.of(pizzaCart));
        when(cartRepository.save(any())).thenAnswer(invocation -> invocation.getArgument(0));
        when(cartPricer.price(any())).thenReturn(CartResponse.empty());

        cartService.addItem("u1", new AddCartItemRequest("dosa", 1), true);

        assertThat(pizzaCart.getRestaurantId()).isEqualTo("r1");
        assertThat(pizzaCart.getLines()).extracting(CartLine::menuItemId).containsExactly("dosa");
    }

    private void givenAvailableDosa() {
        when(menuService.getItem("dosa")).thenReturn(DOSA);
        when(restaurantService.getById("r1")).thenReturn(new RestaurantResponse("r1", "Dosa Junction", "Patel Nagar",
                "Gwalior", 26.208, 78.185, List.of("south indian"), 4.2, LocalTime.of(7, 0), LocalTime.of(22, 0),
                true, null, null));
    }
}
