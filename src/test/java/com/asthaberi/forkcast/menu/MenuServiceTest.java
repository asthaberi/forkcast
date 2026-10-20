package com.asthaberi.forkcast.menu;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.math.BigDecimal;
import java.util.List;
import java.util.Optional;

import com.asthaberi.forkcast.common.exception.ResourceNotFoundException;
import com.asthaberi.forkcast.menu.dto.MenuItemRequest;
import com.asthaberi.forkcast.menu.dto.MenuItemResponse;
import com.asthaberi.forkcast.menu.dto.MenuResponse;
import com.asthaberi.forkcast.restaurant.RestaurantService;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.context.ApplicationEventPublisher;

@ExtendWith(MockitoExtension.class)
class MenuServiceTest {

    @Mock
    private MenuItemRepository menuItemRepository;

    @Mock
    private RestaurantService restaurantService;

    @Mock
    private ApplicationEventPublisher events;

    @InjectMocks
    private MenuService menuService;

    @Test
    void groupsItemsByCategoryAndSortsByName() {
        when(menuItemRepository.findByRestaurantId("r1")).thenReturn(List.of(
                item("Naan", "Breads"),
                item("Paneer Tikka", "Starters"),
                item("Dal Makhani", "Mains"),
                item("Butter Chicken", "Mains")));

        MenuResponse menu = menuService.getMenu("r1");

        assertThat(menu.itemCount()).isEqualTo(4);
        assertThat(menu.categories()).extracting("name").containsExactly("Breads", "Mains", "Starters");
        assertThat(menu.categories().get(1).items()).extracting("name").containsExactly("Butter Chicken", "Dal Makhani");
    }

    @Test
    void getMenuFailsForUnknownRestaurant() {
        doThrow(new ResourceNotFoundException("Restaurant", "nope")).when(restaurantService).requireExists("nope");

        assertThatThrownBy(() -> menuService.getMenu("nope")).isInstanceOf(ResourceNotFoundException.class);
        verify(menuItemRepository, never()).findByRestaurantId(any());
    }

    @Test
    void addItemAppliesDefaults() {
        when(menuItemRepository.save(any())).thenAnswer(invocation -> invocation.getArgument(0));
        MenuItemRequest request = new MenuItemRequest(" Masala Dosa ", null, "Dosas", new BigDecimal("129.00"),
                true, null, null, null);

        MenuItemResponse response = menuService.addItem("r1", request);

        assertThat(response.name()).isEqualTo("Masala Dosa");
        assertThat(response.spiceLevel()).isEqualTo(SpiceLevel.NONE);
        assertThat(response.available()).isTrue();
        assertThat(response.tags()).isEmpty();
    }

    @Test
    void cannotUpdateAnotherRestaurantsItem() {
        when(menuItemRepository.findByIdAndRestaurantId("item1", "other")).thenReturn(Optional.empty());
        MenuItemRequest request = new MenuItemRequest("X", null, "Mains", BigDecimal.TEN, true, null, null, null);

        assertThatThrownBy(() -> menuService.updateItem("other", "item1", request))
                .isInstanceOf(ResourceNotFoundException.class);
        verify(menuItemRepository, never()).save(any());
    }

    private static MenuItem item(String name, String category) {
        return MenuItem.builder().id(name).restaurantId("r1").name(name).category(category)
                .price(new BigDecimal("100.00")).available(true).build();
    }
}
