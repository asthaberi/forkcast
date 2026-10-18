package com.asthaberi.forkcast.cart;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import java.math.BigDecimal;
import java.util.List;

import com.asthaberi.forkcast.cart.dto.CartResponse;
import com.asthaberi.forkcast.cart.dto.CartResponse.CartLineResponse;
import com.asthaberi.forkcast.menu.MenuService;
import com.asthaberi.forkcast.menu.SpiceLevel;
import com.asthaberi.forkcast.menu.dto.MenuItemResponse;
import com.asthaberi.forkcast.menu.dto.MenuResponse;
import com.asthaberi.forkcast.menu.dto.MenuResponse.MenuCategory;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
class CartPricerTest {

    @Mock
    private MenuService menuService;

    private CartPricer pricer;
    private Cart cart;

    @BeforeEach
    void setUp() {
        pricer = new CartPricer(menuService,
                new PricingProperties(new BigDecimal("30.00"), new BigDecimal("499.00"), new BigDecimal("0.05")));
        cart = Cart.emptyFor("user1");
    }

    @Test
    void emptyCartIsFreeAndDoesNotLoadAMenu() {
        CartResponse response = pricer.price(cart);

        assertThat(response.total()).isEqualByComparingTo("0");
        verifyNoInteractions(menuService);
    }

    @Test
    void chargesDeliveryAndTaxBelowFreeDeliveryThreshold() {
        menu(item("dosa", "129.00", true));
        cart.addItem("r1", "Dosa Junction", "dosa", "Masala Dosa", 2);

        CartResponse response = pricer.price(cart);

        assertThat(response.subtotal()).isEqualTo(new BigDecimal("258.00"));
        assertThat(response.deliveryFee()).isEqualTo(new BigDecimal("30.00"));
        assertThat(response.tax()).isEqualTo(new BigDecimal("12.90"));
        assertThat(response.total()).isEqualTo(new BigDecimal("300.90"));
        assertThat(response.itemCount()).isEqualTo(2);
    }

    @Test
    void deliveryIsFreeAtThreshold() {
        menu(item("thali", "499.00", true));
        cart.addItem("r1", "Royal Thali", "thali", "Rajasthani Thali", 1);

        assertThat(pricer.price(cart).deliveryFee()).isEqualTo(new BigDecimal("0.00"));
    }

    @Test
    void taxRoundsHalfUpToPaise() {
        menu(item("chai", "10.10", true));
        cart.addItem("r1", "Cafe", "chai", "Chai", 1);

        // 10.10 * 0.05 = 0.505 -> 0.51
        assertThat(pricer.price(cart).tax()).isEqualTo(new BigDecimal("0.51"));
    }

    @Test
    void usesCurrentMenuPriceAndSkipsUnavailableAndRemovedItems() {
        menu(item("dosa", "149.00", true), item("vada", "99.00", false));
        cart.addItem("r1", "Dosa Junction", "dosa", "Masala Dosa", 1);
        cart.addItem("r1", "Dosa Junction", "vada", "Medu Vada", 1);
        cart.addItem("r1", "Dosa Junction", "gone", "Old Special", 1);

        CartResponse response = pricer.price(cart);

        assertThat(response.subtotal()).isEqualTo(new BigDecimal("149.00"));
        assertThat(response.itemCount()).isEqualTo(1);
        assertThat(response.items()).extracting(CartLineResponse::available).containsExactly(true, false, false);
        CartLineResponse removed = response.items().get(2);
        assertThat(removed.name()).isEqualTo("Old Special");
        assertThat(removed.unitPrice()).isNull();
    }

    private void menu(MenuItemResponse... items) {
        when(menuService.getMenu("r1"))
                .thenReturn(new MenuResponse("r1", items.length, List.of(new MenuCategory("All", List.of(items)))));
    }

    private static MenuItemResponse item(String id, String price, boolean available) {
        return new MenuItemResponse(id, "r1", id, null, "All", new BigDecimal(price), true, SpiceLevel.NONE,
                List.of(), available);
    }
}
