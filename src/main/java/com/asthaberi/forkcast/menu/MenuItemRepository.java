package com.asthaberi.forkcast.menu;

import java.util.List;
import java.util.Optional;

import org.springframework.data.mongodb.repository.MongoRepository;

public interface MenuItemRepository extends MongoRepository<MenuItem, String> {

    List<MenuItem> findByRestaurantId(String restaurantId);

    Optional<MenuItem> findByIdAndRestaurantId(String id, String restaurantId);
}
