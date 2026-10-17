package com.asthaberi.forkcast.common.geo;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.within;

import org.junit.jupiter.api.Test;

class GeoHashTest {

    @Test
    void encodesKnownReferencePoint() {
        // Standard reference value from the original geohash.org examples.
        assertThat(GeoHash.encode(57.64911, 10.40744, 11)).isEqualTo("u4pruydqqvj");
    }

    @Test
    void shorterPrecisionIsAPrefix() {
        String full = GeoHash.encode(26.2124, 78.1772, 9);

        assertThat(GeoHash.encode(26.2124, 78.1772, 7)).isEqualTo(full.substring(0, 7));
    }

    @Test
    void pointsNearTheSameCellCenterShareACell() {
        Coordinates center = GeoHash.decodeCenter(GeoHash.encode(26.2124, 78.1772, 7));

        // ~15 m either side of the center; a precision-7 cell is ~150 m across
        assertThat(GeoHash.encode(center.latitude() + 0.0001, center.longitude() + 0.0001, 7))
                .isEqualTo(GeoHash.encode(center.latitude() - 0.0001, center.longitude() - 0.0001, 7));
    }

    @Test
    void pointsVeryCloseTogetherCanStillStraddleACellEdge() {
        // ~20 m apart but on opposite sides of a cell boundary. This is why the search queries from
        // the cell center and computes each user's distance separately instead of trusting the cache.
        assertThat(GeoHash.encode(26.21240, 78.17720, 7)).isNotEqualTo(GeoHash.encode(26.21250, 78.17735, 7));
    }

    @Test
    void decodedCenterIsCloseToOriginalPoint() {
        Coordinates center = GeoHash.decodeCenter(GeoHash.encode(26.2124, 78.1772, 7));

        // A precision-7 cell is ~153 m wide, so the center is at most ~110 m from any point in it.
        assertThat(GeoUtils.distanceKm(26.2124, 78.1772, center.latitude(), center.longitude()))
                .isLessThan(0.11);
        assertThat(center.latitude()).isCloseTo(26.2124, within(0.001));
    }

    @Test
    void rejectsInvalidInput() {
        assertThatThrownBy(() -> GeoHash.encode(0, 0, 0)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> GeoHash.decodeCenter("abc!")).isInstanceOf(IllegalArgumentException.class);
    }
}
