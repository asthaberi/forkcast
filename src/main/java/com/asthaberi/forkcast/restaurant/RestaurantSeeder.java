package com.asthaberi.forkcast.restaurant;

import java.io.IOException;
import java.io.InputStream;
import java.util.List;

import com.asthaberi.forkcast.restaurant.dto.CreateRestaurantRequest;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.core.io.ClassPathResource;
import org.springframework.stereotype.Component;
import tools.jackson.core.type.TypeReference;
import tools.jackson.databind.json.JsonMapper;

/**
 * Loads sample restaurants on startup when the collection is empty, so the API has data
 * to work with locally. Turned off with forkcast.seed.enabled=false.
 */
@Slf4j
@Component
@ConditionalOnProperty(name = "forkcast.seed.enabled", havingValue = "true")
@RequiredArgsConstructor
class RestaurantSeeder implements ApplicationRunner {

    private static final String SEED_FILE = "data/restaurants.json";

    private final RestaurantRepository restaurantRepository;
    private final JsonMapper jsonMapper;

    @Override
    public void run(ApplicationArguments args) throws IOException {
        if (restaurantRepository.count() > 0) {
            log.info("Restaurants already present, skipping seed");
            return;
        }
        try (InputStream in = new ClassPathResource(SEED_FILE).getInputStream()) {
            List<CreateRestaurantRequest> requests = jsonMapper.readValue(in, new TypeReference<>() {
            });
            restaurantRepository.saveAll(requests.stream().map(RestaurantMapper::toEntity).toList());
            log.info("Seeded {} restaurants from {}", requests.size(), SEED_FILE);
        }
    }
}
