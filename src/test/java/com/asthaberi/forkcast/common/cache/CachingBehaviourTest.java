package com.asthaberi.forkcast.common.cache;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyDouble;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.math.BigDecimal;
import java.util.List;
import java.util.Optional;

import com.asthaberi.forkcast.menu.MenuItem;
import com.asthaberi.forkcast.menu.MenuItemRepository;
import com.asthaberi.forkcast.menu.MenuService;
import com.asthaberi.forkcast.menu.dto.MenuItemRequest;
import com.asthaberi.forkcast.restaurant.NearbyRestaurantFinder;
import com.asthaberi.forkcast.restaurant.RestaurantRepository;
import com.asthaberi.forkcast.restaurant.RestaurantService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.cache.CacheManager;
import org.springframework.cache.annotation.EnableCaching;
import org.springframework.cache.concurrent.ConcurrentMapCacheManager;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.context.junit.jupiter.SpringJUnitConfig;

/**
 * Verifies the caching annotations are wired correctly (hits skip the database, writes evict)
 * using an in-memory cache, so it runs without Redis.
 */
@SpringJUnitConfig(CachingBehaviourTest.Config.class)
class CachingBehaviourTest {

    @Configuration
    @EnableCaching
    @Import({MenuService.class, NearbyRestaurantFinder.class})
    static class Config {

        @Bean
        CacheManager cacheManager() {
            return new ConcurrentMapCacheManager(CacheNames.MENUS, CacheNames.NEARBY_RESTAURANTS);
        }
    }

    @Autowired
    private MenuService menuService;

    @Autowired
    private NearbyRestaurantFinder nearbyRestaurantFinder;

    @Autowired
    private CacheManager cacheManager;

    @MockitoBean
    private MenuItemRepository menuItemRepository;

    @MockitoBean
    private RestaurantRepository restaurantRepository;

    @MockitoBean
    private RestaurantService restaurantService;

    @BeforeEach
    void clearCaches() {
        cacheManager.getCacheNames().forEach(name -> cacheManager.getCache(name).clear());
    }

    @Test
    void secondMenuReadIsServedFromCache() {
        when(menuItemRepository.findByRestaurantId("r1")).thenReturn(List.of());

        menuService.getMenu("r1");
        menuService.getMenu("r1");

        verify(menuItemRepository, times(1)).findByRestaurantId("r1");
    }

    @Test
    void updatingAnItemEvictsThatRestaurantsMenu() {
        MenuItem item = MenuItem.builder().id("i1").restaurantId("r1").name("Dosa").category("Dosas")
                .price(BigDecimal.TEN).build();
        when(menuItemRepository.findByRestaurantId(any())).thenReturn(List.of(item));
        when(menuItemRepository.findByIdAndRestaurantId("i1", "r1")).thenReturn(Optional.of(item));
        when(menuItemRepository.save(any())).thenAnswer(invocation -> invocation.getArgument(0));

        menuService.getMenu("r1");
        menuService.getMenu("r2");
        menuService.updateItem("r1", "i1",
                new MenuItemRequest("Dosa", null, "Dosas", BigDecimal.ONE, true, null, null, null));
        menuService.getMenu("r1");
        menuService.getMenu("r2");

        verify(menuItemRepository, times(2)).findByRestaurantId("r1");
        verify(menuItemRepository, times(1)).findByRestaurantId("r2");
    }

    @Test
    void nearbyLookupIsCachedPerCellRadiusAndCuisine() {
        when(restaurantRepository.findNearby(anyDouble(), anyDouble(), anyDouble(), any(), anyInt()))
                .thenReturn(List.of());

        nearbyRestaurantFinder.findInCell("tsq4b2c", 5.0, null);
        nearbyRestaurantFinder.findInCell("tsq4b2c", 5.0, null);   // hit
        nearbyRestaurantFinder.findInCell("tsq4b2c", 3.0, null);   // different radius -> miss
        nearbyRestaurantFinder.findInCell("tsq4b2c", 5.0, "pizza"); // different cuisine -> miss

        verify(restaurantRepository, times(2)).findNearby(anyDouble(), anyDouble(), anyDouble(), isNull(), anyInt());
        verify(restaurantRepository, times(1)).findNearby(anyDouble(), anyDouble(), anyDouble(), any(String.class), anyInt());
        assertThat(cacheManager.getCache(CacheNames.NEARBY_RESTAURANTS).get("tsq4b2c:5.0:any")).isNotNull();
    }
}
