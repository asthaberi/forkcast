package com.asthaberi.forkcast.common.geo;

/**
 * Geohash encoding: splits the world into a grid and names each cell with a base-32 string.
 * Each extra character makes the cell ~32x smaller; at precision 7 a cell is about 153 m x 153 m.
 * Nearby points share a prefix, which is what makes geohashes useful as cache keys.
 *
 * Bits alternate between longitude and latitude: each bit halves the current range and records
 * whether the point lies in the upper (1) or lower (0) half; every 5 bits become one character.
 */
public final class GeoHash {

    private static final String BASE32 = "0123456789bcdefghjkmnpqrstuvwxyz";

    private GeoHash() {
    }

    public static String encode(double latitude, double longitude, int precision) {
        if (precision < 1 || precision > 12) {
            throw new IllegalArgumentException("precision must be between 1 and 12");
        }
        double[] latRange = {-90.0, 90.0};
        double[] lngRange = {-180.0, 180.0};
        StringBuilder hash = new StringBuilder(precision);
        boolean longitudeBit = true;
        int bits = 0;
        int index = 0;

        while (hash.length() < precision) {
            double[] range = longitudeBit ? lngRange : latRange;
            double value = longitudeBit ? longitude : latitude;
            double mid = (range[0] + range[1]) / 2;
            if (value >= mid) {
                index = (index << 1) | 1;
                range[0] = mid;
            } else {
                index <<= 1;
                range[1] = mid;
            }
            longitudeBit = !longitudeBit;

            if (++bits == 5) {
                hash.append(BASE32.charAt(index));
                bits = 0;
                index = 0;
            }
        }
        return hash.toString();
    }

    /** Center point of the cell a geohash names. */
    public static Coordinates decodeCenter(String hash) {
        double[] latRange = {-90.0, 90.0};
        double[] lngRange = {-180.0, 180.0};
        boolean longitudeBit = true;

        for (char c : hash.toCharArray()) {
            int index = BASE32.indexOf(c);
            if (index < 0) {
                throw new IllegalArgumentException("Invalid geohash character: " + c);
            }
            for (int mask = 16; mask > 0; mask >>= 1) {
                double[] range = longitudeBit ? lngRange : latRange;
                double mid = (range[0] + range[1]) / 2;
                if ((index & mask) != 0) {
                    range[0] = mid;
                } else {
                    range[1] = mid;
                }
                longitudeBit = !longitudeBit;
            }
        }
        return new Coordinates((latRange[0] + latRange[1]) / 2, (lngRange[0] + lngRange[1]) / 2);
    }
}
