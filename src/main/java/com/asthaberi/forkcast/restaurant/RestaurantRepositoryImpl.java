package com.asthaberi.forkcast.restaurant;

import java.util.List;
import java.util.Locale;

import lombok.RequiredArgsConstructor;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.data.mongodb.core.geo.GeoJsonPoint;
import org.springframework.data.mongodb.core.query.Criteria;
import org.springframework.data.mongodb.core.query.Query;

/**
 * Spring Data finds this class by the "Impl" suffix and plugs it into {@link RestaurantRepository}.
 */
@RequiredArgsConstructor
class RestaurantRepositoryImpl implements RestaurantRepositoryCustom {

    private static final double METERS_PER_KM = 1000.0;

    private final MongoTemplate mongoTemplate;

    @Override
    public List<Restaurant> findNearby(double latitude, double longitude, double radiusKm, String cuisine, int limit) {
        // $nearSphere on a GeoJSON point: uses the 2dsphere index, returns results sorted by distance,
        // and $maxDistance is in meters.
        Criteria criteria = Criteria.where("location")
                .nearSphere(new GeoJsonPoint(longitude, latitude))
                .maxDistance(radiusKm * METERS_PER_KM);

        if (cuisine != null && !cuisine.isBlank()) {
            // Equality on an array field matches if any element equals the value.
            criteria = criteria.and("cuisines").is(cuisine.trim().toLowerCase(Locale.ROOT));
        }

        Query query = Query.query(criteria).limit(limit);
        return mongoTemplate.find(query, Restaurant.class);
    }
}
