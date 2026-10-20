package com.asthaberi.forkcast.review;

import java.util.List;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.mongodb.repository.MongoRepository;

public interface ReviewRepository extends MongoRepository<Review, String> {

    Page<Review> findByRestaurantIdOrderByCreatedAtDesc(String restaurantId, Pageable pageable);

    /** The reviews a summary is written from: the most recent ones. */
    List<Review> findTop40ByRestaurantIdOrderByCreatedAtDesc(String restaurantId);
}
