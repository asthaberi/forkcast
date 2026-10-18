package com.asthaberi.forkcast.cart;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.asthaberi.forkcast.common.exception.BusinessException;
import com.asthaberi.forkcast.common.exception.ErrorCode;
import com.asthaberi.forkcast.common.exception.ResourceNotFoundException;
import org.junit.jupiter.api.Test;

class CartTest {

    private final Cart cart = Cart.emptyFor("user1");

    @Test
    void addingTheSameItemTwiceIncreasesQuantity() {
        cart.addItem("r1", "Dosa Junction", "dosa", "Masala Dosa", 1);
        cart.addItem("r1", "Dosa Junction", "dosa", "Masala Dosa", 2);

        assertThat(cart.getLines()).singleElement().extracting(CartLine::quantity).isEqualTo(3);
        assertThat(cart.getRestaurantId()).isEqualTo("r1");
    }

    @Test
    void rejectsItemsFromAnotherRestaurant() {
        cart.addItem("r1", "Dosa Junction", "dosa", "Masala Dosa", 1);

        assertThatThrownBy(() -> cart.addItem("r2", "Pizza Piazza", "pizza", "Margherita", 1))
                .isInstanceOfSatisfying(BusinessException.class, ex -> {
                    assertThat(ex.code()).isEqualTo(ErrorCode.CART_RESTAURANT_MISMATCH);
                    assertThat(ex.details()).containsEntry("currentRestaurantId", "r1");
                });
        assertThat(cart.getLines()).hasSize(1);
    }

    @Test
    void enforcesPerItemQuantityLimitIncludingExistingQuantity() {
        cart.addItem("r1", "Dosa Junction", "dosa", "Masala Dosa", 15);

        assertThatThrownBy(() -> cart.addItem("r1", "Dosa Junction", "dosa", "Masala Dosa", 6))
                .isInstanceOfSatisfying(BusinessException.class,
                        ex -> assertThat(ex.code()).isEqualTo(ErrorCode.QUANTITY_LIMIT_EXCEEDED));
        assertThat(cart.getLines().getFirst().quantity()).isEqualTo(15);
    }

    @Test
    void settingQuantityToZeroRemovesTheLastItemAndFreesTheCart() {
        cart.addItem("r1", "Dosa Junction", "dosa", "Masala Dosa", 2);

        cart.setQuantity("dosa", 0);

        assertThat(cart.isEmpty()).isTrue();
        assertThat(cart.getRestaurantId()).isNull();
        // An empty cart accepts any restaurant again
        cart.addItem("r2", "Pizza Piazza", "pizza", "Margherita", 1);
        assertThat(cart.getRestaurantId()).isEqualTo("r2");
    }

    @Test
    void updatingAnItemNotInTheCartIsNotFound() {
        assertThatThrownBy(() -> cart.setQuantity("ghost", 1)).isInstanceOf(ResourceNotFoundException.class);
        assertThatThrownBy(() -> cart.removeItem("ghost")).isInstanceOf(ResourceNotFoundException.class);
    }
}
