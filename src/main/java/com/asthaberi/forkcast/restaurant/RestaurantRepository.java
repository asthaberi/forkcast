package com.asthaberi.forkcast.restaurant;

import org.springframework.data.mongodb.repository.MongoRepository;

/**
 * Standard CRUD comes from {@link MongoRepository}; the geo query lives in
 * {@link RestaurantRepositoryCustom}, which Spring Data merges into this interface.
 */
public interface RestaurantRepository extends MongoRepository<Restaurant, String>, RestaurantRepositoryCustom {
}
