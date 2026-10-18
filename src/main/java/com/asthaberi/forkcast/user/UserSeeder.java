package com.asthaberi.forkcast.user;

import java.time.Clock;
import java.util.Locale;

import com.asthaberi.forkcast.restaurant.RestaurantRepository;
import com.asthaberi.forkcast.user.SeedUserProperties.SeedUser;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.core.annotation.Order;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Component;

/**
 * Creates the demo accounts from configuration (skipping any that already exist) and links the owner
 * account to its restaurant. Runs after the restaurant and menu seeders.
 */
@Slf4j
@Component
@ConditionalOnProperty(name = "forkcast.seed.enabled", havingValue = "true")
@Order(3)
@RequiredArgsConstructor
class UserSeeder implements ApplicationRunner {

    private final SeedUserProperties properties;
    private final UserRepository userRepository;
    private final RestaurantRepository restaurantRepository;
    private final PasswordEncoder passwordEncoder;
    private final Clock clock;

    @Override
    public void run(ApplicationArguments args) {
        for (SeedUser seed : properties.users()) {
            String email = seed.email().toLowerCase(Locale.ROOT);
            User user = userRepository.findByEmail(email).orElseGet(() -> {
                log.info("Seeding demo user {} with roles {}", email, seed.roles());
                return userRepository.save(User.create(seed.name(), email, passwordEncoder.encode(seed.password()),
                        seed.roles(), clock.instant()));
            });
            if (seed.owns() != null) {
                restaurantRepository.findByName(seed.owns())
                        .filter(restaurant -> restaurant.getOwnerId() == null)
                        .ifPresent(restaurant -> {
                            restaurant.setOwnerId(user.getId());
                            restaurantRepository.save(restaurant);
                            log.info("Linked {} as owner of {}", email, restaurant.getName());
                        });
            }
        }
    }
}
