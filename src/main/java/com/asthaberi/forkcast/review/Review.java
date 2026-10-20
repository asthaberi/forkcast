package com.asthaberi.forkcast.review;

import java.time.Instant;

import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;
import org.springframework.data.annotation.Id;
import org.springframework.data.mongodb.core.index.CompoundIndex;
import org.springframework.data.mongodb.core.index.Indexed;
import org.springframework.data.mongodb.core.mapping.Document;

@Document(collection = "reviews")
// Newest reviews of a restaurant first, without an in-memory sort
@CompoundIndex(name = "restaurant_created_at", def = "{'restaurantId': 1, 'createdAt': -1}")
@Getter
@NoArgsConstructor(access = AccessLevel.PRIVATE)
public class Review {

    @Id
    private String id;

    private String restaurantId;

    // One review per delivered order, even with concurrent submissions. Sparse because seeded demo reviews
    // have no order (Spring Data doesn't write null fields, so they're simply left out of the index).
    @Indexed(unique = true, sparse = true)
    private String orderId;

    @Indexed
    private String userId;

    // A display name snapshot ("Astha B."), so listing reviews needs no user lookups and shows no email
    private String authorName;

    private int rating;

    private String text;

    private Instant createdAt;

    public static Review of(String restaurantId, String orderId, String userId, String authorName, int rating,
                            String text, Instant createdAt) {
        Review review = new Review();
        review.restaurantId = restaurantId;
        review.orderId = orderId;
        review.userId = userId;
        review.authorName = authorName;
        review.rating = rating;
        review.text = text;
        review.createdAt = createdAt;
        return review;
    }

    public boolean isVerifiedOrder() {
        return orderId != null;
    }
}
