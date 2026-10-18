package com.asthaberi.forkcast.order;

import java.util.Collection;
import java.util.Optional;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.mongodb.repository.MongoRepository;

public interface OrderRepository extends MongoRepository<Order, String> {

    Optional<Order> findByUserIdAndIdempotencyKey(String userId, String idempotencyKey);

    Optional<Order> findByIdAndUserId(String id, String userId);

    Page<Order> findByUserIdOrderByPlacedAtDesc(String userId, Pageable pageable);

    Optional<Order> findByIdAndRestaurantId(String id, String restaurantId);

    Page<Order> findByRestaurantIdOrderByPlacedAtDesc(String restaurantId, Pageable pageable);

    Page<Order> findByRestaurantIdAndStatusInOrderByPlacedAtDesc(String restaurantId, Collection<OrderStatus> statuses,
                                                                Pageable pageable);
}
