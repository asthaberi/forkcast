package com.asthaberi.forkcast.ratelimit;

import io.micrometer.core.instrument.MeterRegistry;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.web.servlet.handler.MappedInterceptor;

@Slf4j
@Configuration
public class RateLimitConfig {

    @Bean
    RateLimiter rateLimiter(RateLimitProperties properties, StringRedisTemplate redisTemplate,
                            MeterRegistry meterRegistry) {
        if (!properties.enabled()) {
            log.warn("Rate limiting is disabled (forkcast.rate-limit.enabled=false)");
            return RateLimiter.DISABLED;
        }
        return new RedisRateLimiter(redisTemplate, properties, meterRegistry);
    }

    /** Registered as a MappedInterceptor bean, which Spring MVC picks up for every handler mapping. */
    @Bean
    MappedInterceptor rateLimitInterceptor(RateLimiter rateLimiter) {
        return new MappedInterceptor(new String[] {"/api/**"}, new RateLimitInterceptor(rateLimiter));
    }
}
