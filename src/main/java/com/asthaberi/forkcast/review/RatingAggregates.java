package com.asthaberi.forkcast.review;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import com.mongodb.client.model.Filters;
import lombok.RequiredArgsConstructor;
import org.bson.Document;
import org.bson.types.ObjectId;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.stereotype.Component;

/**
 * A restaurant's review count, star total and average rating.
 *
 * Adding a review updates all three in ONE MongoDB update with an aggregation pipeline: increment the count
 * and total, then derive the average from the new values, all inside the database. Reading the restaurant,
 * computing in Java and saving would lose updates when reviews arrive at the same time.
 */
@Component
@RequiredArgsConstructor
public class RatingAggregates {

    private static final String RESTAURANTS = "restaurants";

    private final MongoTemplate mongoTemplate;

    void addRating(String restaurantId, int stars) {
        // Stage 1: count + 1 and total + stars (fields may not exist yet, hence $ifNull)
        Document increment = new Document("$set", new Document()
                .append("reviewCount", add(ifNull("$reviewCount"), 1))
                .append("ratingTotal", add(ifNull("$ratingTotal"), stars)));
        // Stage 2: the average from the values stage 1 just wrote, rounded to one decimal
        Document average = new Document("$set", new Document("rating", new Document("$round",
                List.of(new Document("$divide", List.of("$ratingTotal", "$reviewCount")), 1))));
        mongoTemplate.getCollection(RESTAURANTS)
                .updateOne(Filters.eq("_id", new ObjectId(restaurantId)), List.of(increment, average));
    }

    private static Document add(Object value, int amount) {
        return new Document("$add", List.of(value, amount));
    }

    private static Document ifNull(String field) {
        return new Document("$ifNull", List.of(field, 0));
    }

    /** Rebuilds a restaurant's aggregates from its reviews (used after seeding). */
    void recompute(String restaurantId) {
        Document totals = mongoTemplate.getCollection("reviews").aggregate(List.of(
                new Document("$match", new Document("restaurantId", restaurantId)),
                new Document("$group", new Document("_id", null)
                        .append("count", new Document("$sum", 1))
                        .append("total", new Document("$sum", "$rating"))))).first();
        int count = totals == null ? 0 : totals.getInteger("count");
        int total = totals == null ? 0 : totals.getInteger("total");
        mongoTemplate.getCollection(RESTAURANTS).updateOne(Filters.eq("_id", new ObjectId(restaurantId)),
                new Document("$set", new Document("reviewCount", count).append("ratingTotal", total)
                        .append("rating", count == 0 ? 0.0 : Math.round(10.0 * total / count) / 10.0)));
    }

    /** How many reviews gave 5, 4, 3, 2 and 1 stars. */
    public Map<Integer, Integer> distribution(String restaurantId) {
        Map<Integer, Integer> counts = new LinkedHashMap<>();
        for (int stars = 5; stars >= 1; stars--) {
            counts.put(stars, 0);
        }
        mongoTemplate.getCollection("reviews").aggregate(List.of(
                        new Document("$match", new Document("restaurantId", restaurantId)),
                        new Document("$group", new Document("_id", "$rating").append("count", new Document("$sum", 1)))))
                .forEach(row -> counts.put(row.getInteger("_id"), row.getInteger("count")));
        return counts;
    }
}
