package com.asthaberi.forkcast.menu;

import java.math.BigDecimal;
import java.util.List;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import org.springframework.data.annotation.Id;
import org.springframework.data.mongodb.core.index.Indexed;
import org.springframework.data.mongodb.core.mapping.Document;

/**
 * One dish. Stored in its own collection (not embedded in the restaurant) so items can be
 * updated individually and, later, indexed one by one for semantic search.
 */
@Document(collection = "menu_items")
@Getter
@Setter
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class MenuItem {

    @Id
    private String id;

    @Indexed
    private String restaurantId;

    private String name;

    private String description;

    private String category;

    // BigDecimal, never double, for money; stored as Decimal128 (see MongoConfig).
    private BigDecimal price;

    private boolean vegetarian;

    private SpiceLevel spiceLevel;

    private List<String> tags;

    private boolean available;
}
