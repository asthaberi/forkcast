package com.asthaberi.forkcast.restaurant;

import java.time.LocalTime;
import java.util.List;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Serving radius rules, bound from {@code forkcast.search.*} in application.yml.
 * During peak hours the radius shrinks so deliveries stay fast when riders are busy.
 */
@ConfigurationProperties(prefix = "forkcast.search")
public record SearchProperties(double normalRadiusKm, double peakRadiusKm, List<PeakWindow> peakWindows) {

    public SearchProperties {
        peakWindows = peakWindows == null ? List.of() : List.copyOf(peakWindows);
    }

    public double defaultRadiusAt(LocalTime time) {
        boolean peak = peakWindows.stream().anyMatch(window -> window.contains(time));
        return peak ? peakRadiusKm : normalRadiusKm;
    }

    /** A peak window from startHour (inclusive) to endHour (exclusive), e.g. 13-14 is 1:00 PM to 1:59 PM. */
    public record PeakWindow(int startHour, int endHour) {

        boolean contains(LocalTime time) {
            int hour = time.getHour();
            return hour >= startHour && hour < endHour;
        }
    }
}
