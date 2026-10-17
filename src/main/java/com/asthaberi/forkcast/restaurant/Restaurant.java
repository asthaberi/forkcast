package com.asthaberi.forkcast.restaurant;

import java.time.LocalTime;
import java.util.List;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import org.springframework.data.annotation.Id;
import org.springframework.data.mongodb.core.geo.GeoJsonPoint;
import org.springframework.data.mongodb.core.index.GeoSpatialIndexType;
import org.springframework.data.mongodb.core.index.GeoSpatialIndexed;
import org.springframework.data.mongodb.core.index.Indexed;
import org.springframework.data.mongodb.core.mapping.Document;

@Document(collection = "restaurants")
@Getter
@Setter
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class Restaurant {

    @Id
    private String id;

    @Indexed
    private String name;

    private String address;

    private String city;

    // GeoJSON stores coordinates as [longitude, latitude] (x, y), not [lat, lng].
    // The 2dsphere index lets MongoDB answer "restaurants near me" without scanning every document.
    @GeoSpatialIndexed(type = GeoSpatialIndexType.GEO_2DSPHERE)
    private GeoJsonPoint location;

    // Stored lowercase so a cuisine filter can be an exact, index-friendly match.
    @Indexed
    private List<String> cuisines;

    private LocalTime opensAt;

    private LocalTime closesAt;

    private double rating;

    private String imageUrl;

    public double latitude() {
        return location.getY();
    }

    public double longitude() {
        return location.getX();
    }

    public boolean isOpenAt(LocalTime time) {
        if (opensAt == null || closesAt == null) {
            return false;
        }
        if (opensAt.isBefore(closesAt)) {
            return !time.isBefore(opensAt) && time.isBefore(closesAt);
        }
        // Overnight hours, e.g. 18:00 - 03:00
        return !time.isBefore(opensAt) || time.isBefore(closesAt);
    }
}
