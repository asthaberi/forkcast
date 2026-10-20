package com.asthaberi.forkcast.common.cache;

import static org.assertj.core.api.Assertions.assertThat;

import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.time.LocalTime;
import java.util.List;

import com.asthaberi.forkcast.menu.SpiceLevel;
import com.asthaberi.forkcast.menu.dto.MenuItemResponse;
import com.asthaberi.forkcast.menu.dto.MenuResponse;
import com.asthaberi.forkcast.menu.dto.MenuResponse.MenuCategory;
import com.asthaberi.forkcast.restaurant.RestaurantSnapshot;
import org.junit.jupiter.api.Test;
import org.springframework.data.redis.serializer.JacksonJsonRedisSerializer;
import tools.jackson.databind.JavaType;
import tools.jackson.databind.json.JsonMapper;

/**
 * Cached values must survive a JSON round trip exactly, or a cache hit returns different data
 * than a cache miss. Uses the same serializer type as CacheConfig.
 */
class CacheSerializationTest {

    private final JsonMapper jsonMapper = JsonMapper.builder().build();

    @Test
    void menuRoundTrips() {
        MenuResponse menu = new MenuResponse("r1", 1, List.of(new MenuCategory("Dosas", List.of(
                new MenuItemResponse("i1", "r1", "Masala Dosa", "Crispy", "Dosas", new BigDecimal("129.50"), true,
                        SpiceLevel.MILD, List.of("breakfast"), true)))));
        var serializer = new JacksonJsonRedisSerializer<MenuResponse>(jsonMapper, jsonMapper.constructType(MenuResponse.class));

        byte[] json = serializer.serialize(menu);

        assertThat(serializer.deserialize(json)).isEqualTo(menu);
        // Plain JSON with no Java class names inside
        assertThat(new String(json, StandardCharsets.UTF_8)).doesNotContain("com.asthaberi");
    }

    @Test
    void nearbySnapshotListRoundTrips() {
        List<RestaurantSnapshot> snapshots = List.of(new RestaurantSnapshot("r1", "Midnight Biryani Co.", "Morar",
                "Gwalior", 26.225, 78.205, List.of("biryani"), 4.5, 0, LocalTime.of(18, 0), LocalTime.of(3, 0), null));
        JavaType type = jsonMapper.getTypeFactory().constructCollectionType(List.class, RestaurantSnapshot.class);
        var serializer = new JacksonJsonRedisSerializer<List<RestaurantSnapshot>>(jsonMapper, type);

        assertThat(serializer.deserialize(serializer.serialize(snapshots))).isEqualTo(snapshots);
    }
}
