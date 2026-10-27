package com.asthaberi.forkcast.recommendation;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Clock;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Date;
import java.util.HexFormat;
import java.util.List;

import com.asthaberi.forkcast.common.cache.CacheNames;
import com.asthaberi.forkcast.order.OrderStatus;
import lombok.RequiredArgsConstructor;
import org.bson.Document;
import org.springframework.cache.annotation.Cacheable;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.stereotype.Component;

/**
 * The dishes most people around you ordered recently: the fallback for customers with no history yet (the
 * "cold start" problem) and the filler when personal matches run out. Ranked by how many different
 * customers ordered a dish, not how many portions, so one big order can't make a dish "popular".
 *
 * The aggregation reads every order of the nearby restaurants from the last 30 days, so its cost grows with
 * order volume (found by load testing: 8.5 s at ~20,000 orders). It's cached per area for 10 minutes.
 * At larger scale it would be precomputed, e.g. a scheduled $merge into a popularity collection.
 */
@Component
@RequiredArgsConstructor
public class PopularDishes {

    static final Duration WINDOW = Duration.ofDays(30);
    private static final int LIMIT = 50;

    private final MongoTemplate mongoTemplate;
    private final Clock clock;

    /** @return dish ids with the number of different customers who ordered each, most popular first */
    @Cacheable(cacheNames = CacheNames.POPULAR_DISHES,
            key = "T(com.asthaberi.forkcast.recommendation.PopularDishes).areaKey(#restaurantIds)")
    public List<Popularity> at(Collection<String> restaurantIds) {
        if (restaurantIds.isEmpty()) {
            return List.of();
        }
        List<Popularity> popular = new ArrayList<>();
        mongoTemplate.getCollection("orders").aggregate(List.of(
                // Served by the restaurant_status_placed_at index
                new Document("$match", new Document("restaurantId", new Document("$in", List.copyOf(restaurantIds)))
                        .append("status", new Document("$nin", List.of(OrderStatus.CANCELLED.name(),
                                OrderStatus.REJECTED.name())))
                        .append("placedAt", new Document("$gte", Date.from(clock.instant().minus(WINDOW))))),
                new Document("$unwind", "$lines"),
                new Document("$group", new Document("_id", "$lines.menuItemId")
                        .append("customers", new Document("$addToSet", "$userId"))),
                new Document("$project", new Document("customers", new Document("$size", "$customers"))),
                new Document("$sort", new Document("customers", -1).append("_id", 1)),
                new Document("$limit", LIMIT)))
                .forEach(row -> popular.add(new Popularity(row.getString("_id"), row.getInteger("customers"))));
        return popular;
    }

    /** A short, order-independent cache key for a set of restaurants (there can be hundreds nearby). */
    public static String areaKey(Collection<String> restaurantIds) {
        try {
            MessageDigest sha256 = MessageDigest.getInstance("SHA-256");
            restaurantIds.stream().sorted().forEach(id -> sha256.update((id + ",").getBytes(StandardCharsets.UTF_8)));
            return HexFormat.of().formatHex(sha256.digest(), 0, 16);
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }
}
