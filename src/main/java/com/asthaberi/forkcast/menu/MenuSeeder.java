package com.asthaberi.forkcast.menu;

import java.io.IOException;
import java.io.InputStream;
import java.util.List;

import com.asthaberi.forkcast.menu.dto.MenuItemRequest;
import com.asthaberi.forkcast.restaurant.RestaurantRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.core.annotation.Order;
import org.springframework.core.io.ClassPathResource;
import org.springframework.stereotype.Component;
import tools.jackson.core.type.TypeReference;
import tools.jackson.databind.json.JsonMapper;

/**
 * Loads sample menus for the seeded restaurants. Runs after RestaurantSeeder (@Order 2 vs 1)
 * because items are linked to restaurants by name in the seed file.
 */
@Slf4j
@Component
@ConditionalOnProperty(name = "forkcast.seed.enabled", havingValue = "true")
@Order(2)
@RequiredArgsConstructor
class MenuSeeder implements ApplicationRunner {

    private static final String SEED_FILE = "data/menus.json";

    private final MenuItemRepository menuItemRepository;
    private final RestaurantRepository restaurantRepository;
    private final JsonMapper jsonMapper;

    @Override
    public void run(ApplicationArguments args) throws IOException {
        if (menuItemRepository.count() > 0) {
            log.info("Menu items already present, skipping seed");
            return;
        }
        try (InputStream in = new ClassPathResource(SEED_FILE).getInputStream()) {
            List<MenuSeed> menus = jsonMapper.readValue(in, new TypeReference<>() {
            });
            int count = 0;
            for (MenuSeed menu : menus) {
                var restaurant = restaurantRepository.findByName(menu.restaurant());
                if (restaurant.isEmpty()) {
                    log.warn("Skipping menu for unknown restaurant '{}'", menu.restaurant());
                    continue;
                }
                String restaurantId = restaurant.get().getId();
                menuItemRepository.saveAll(menu.items().stream()
                        .map(item -> MenuMapper.toEntity(restaurantId, item))
                        .toList());
                count += menu.items().size();
            }
            log.info("Seeded {} menu items from {}", count, SEED_FILE);
        }
    }

    record MenuSeed(String restaurant, List<MenuItemRequest> items) {
    }
}
