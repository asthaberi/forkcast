package com.asthaberi.forkcast.common.config;

import java.time.Clock;
import java.time.ZoneId;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
public class ClockConfig {

    /**
     * Injecting a Clock instead of calling LocalTime.now() keeps time-based logic
     * (open hours, peak hours) testable with Clock.fixed(...). The zone is explicit so the
     * server's own time zone (often UTC in the cloud) doesn't change business rules.
     */
    @Bean
    Clock clock(@Value("${forkcast.time-zone}") String timeZone) {
        return Clock.system(ZoneId.of(timeZone));
    }
}
