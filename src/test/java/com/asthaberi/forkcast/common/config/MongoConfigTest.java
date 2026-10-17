package com.asthaberi.forkcast.common.config;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.LocalTime;

import org.junit.jupiter.api.Test;

class MongoConfigTest {

    @Test
    void localTimeRoundTripsAsHourMinuteString() {
        var write = new MongoConfig.LocalTimeToStringConverter();
        var read = new MongoConfig.StringToLocalTimeConverter();

        assertThat(write.convert(LocalTime.of(18, 0))).isEqualTo("18:00");
        assertThat(read.convert("03:00")).isEqualTo(LocalTime.of(3, 0));
        assertThat(read.convert(write.convert(LocalTime.of(23, 30)))).isEqualTo(LocalTime.of(23, 30));
    }
}
