package com.asthaberi.forkcast.common.cache;

import java.time.Duration;
import java.util.List;

import com.asthaberi.forkcast.menu.dto.MenuResponse;
import com.asthaberi.forkcast.restaurant.RestaurantSnapshot;
import org.springframework.boot.cache.autoconfigure.RedisCacheManagerBuilderCustomizer;
import org.springframework.cache.annotation.CachingConfigurer;
import org.springframework.cache.annotation.EnableCaching;
import org.springframework.cache.interceptor.CacheErrorHandler;
import org.springframework.cache.interceptor.LoggingCacheErrorHandler;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.data.redis.cache.RedisCacheConfiguration;
import org.springframework.data.redis.serializer.JacksonJsonRedisSerializer;
import org.springframework.data.redis.serializer.RedisSerializationContext.SerializationPair;
import tools.jackson.databind.JavaType;
import tools.jackson.databind.json.JsonMapper;

@Configuration
@EnableCaching
public class CacheConfig implements CachingConfigurer {

    /**
     * Per-cache TTL and a typed JSON serializer for each cached value. Typed serializers store plain
     * JSON (readable in redis-cli) with no Java class names embedded, and don't require Serializable.
     * Statistics expose hit/miss counts at /actuator/metrics/cache.gets.
     */
    @Bean
    RedisCacheManagerBuilderCustomizer redisCacheCustomizer(JsonMapper jsonMapper, ForkcastCacheProperties properties) {
        JavaType menuType = jsonMapper.constructType(MenuResponse.class);
        JavaType nearbyType = jsonMapper.getTypeFactory().constructCollectionType(List.class, RestaurantSnapshot.class);

        return builder -> builder
                .enableStatistics()
                .withCacheConfiguration(CacheNames.MENUS,
                        cacheConfiguration(jsonMapper, menuType, properties.menuTtl()))
                .withCacheConfiguration(CacheNames.NEARBY_RESTAURANTS,
                        cacheConfiguration(jsonMapper, nearbyType, properties.nearbyTtl()));
    }

    /**
     * If Redis is down, log and carry on: the cached method runs normally against MongoDB.
     * The cache is an optimization, so its failure shouldn't take the API down.
     */
    @Override
    public CacheErrorHandler errorHandler() {
        return new LoggingCacheErrorHandler(false);
    }

    private static RedisCacheConfiguration cacheConfiguration(JsonMapper jsonMapper, JavaType type, Duration ttl) {
        return RedisCacheConfiguration.defaultCacheConfig()
                .entryTtl(ttl)
                .disableCachingNullValues()
                .serializeValuesWith(SerializationPair.fromSerializer(new JacksonJsonRedisSerializer<>(jsonMapper, type)));
    }
}
