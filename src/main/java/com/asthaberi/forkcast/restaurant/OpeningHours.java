package com.asthaberi.forkcast.restaurant;

import java.time.LocalTime;

final class OpeningHours {

    private OpeningHours() {
    }

    static boolean isOpen(LocalTime opensAt, LocalTime closesAt, LocalTime time) {
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
