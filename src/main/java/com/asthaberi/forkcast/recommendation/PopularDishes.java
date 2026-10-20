package com.asthaberi.forkcast.recommendation;

import java.time.Clock;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Date;
import java.util.List;

import com.asthaberi.forkcast.order.OrderStatus;
import lombok.RequiredArgsConstructor;
import org.bson.Document;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.stereotype.Component;

/**
 * The dishes most people around you ordered recently: the fallback for customers with no history yet (the
 * "cold start" problem) and the filler when personal matches run out. Ranked by how many different
 * customers ordered a dish, not how many portions, so one big order can't make a dish "popular".
 */
@Component
@RequiredArgsConstructor
class PopularDishes {

    static final Duration WINDOW = Duration.ofDays(30);
    private static final int LIMIT = 50;

    private final MongoTemplate mongoTemplate;
    private final Clock clock;

    /** @return dish ids with the number of different customers who ordered each, most popular first */
    List<Popularity> at(Collection<String> restaurantIds) {
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

    record Popularity(String menuItemId, int customers) {
    }
}
