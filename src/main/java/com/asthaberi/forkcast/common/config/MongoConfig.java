package com.asthaberi.forkcast.common.config;

import java.time.LocalTime;
import java.util.List;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.convert.converter.Converter;
import org.springframework.data.convert.ReadingConverter;
import org.springframework.data.convert.WritingConverter;
import org.springframework.data.mongodb.core.convert.MongoCustomConversions;

@Configuration
public class MongoConfig {

    /**
     * By default Spring Data stores a LocalTime as a full Date built from today's date and the JVM's
     * time zone, so "18:00" read back on a server in another zone becomes a different time.
     * Storing "HH:mm" strings keeps opening hours zone-independent and readable in the database.
     */
    @Bean
    MongoCustomConversions mongoCustomConversions() {
        return new MongoCustomConversions(List.of(
                new LocalTimeToStringConverter(),
                new StringToLocalTimeConverter()));
    }

    @WritingConverter
    static class LocalTimeToStringConverter implements Converter<LocalTime, String> {

        @Override
        public String convert(LocalTime source) {
            return source.toString();
        }
    }

    @ReadingConverter
    static class StringToLocalTimeConverter implements Converter<String, LocalTime> {

        @Override
        public LocalTime convert(String source) {
            return LocalTime.parse(source);
        }
    }
}
