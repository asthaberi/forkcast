package com.asthaberi.forkcast.common.geo;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.within;

import org.junit.jupiter.api.Test;

class GeoUtilsTest {

    @Test
    void samePointIsZeroKmApart() {
        assertThat(GeoUtils.distanceKm(26.2124, 78.1772, 26.2124, 78.1772)).isZero();
    }

    @Test
    void oneDegreeOfLatitudeIsAbout111Km() {
        assertThat(GeoUtils.distanceKm(26.0, 78.0, 27.0, 78.0)).isCloseTo(111.19, within(0.05));
    }

    @Test
    void knownCityDistance() {
        // Gwalior to Agra is roughly 108 km in a straight line.
        double gwaliorToAgra = GeoUtils.distanceKm(26.2183, 78.1828, 27.1767, 78.0081);
        assertThat(gwaliorToAgra).isBetween(105.0, 111.0);
    }
}
