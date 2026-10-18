package com.asthaberi.forkcast.order.events;

import lombok.extern.slf4j.Slf4j;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.data.redis.connection.RedisConnectionFactory;
import org.springframework.data.redis.listener.ChannelTopic;
import org.springframework.data.redis.listener.RedisMessageListenerContainer;
import org.springframework.scheduling.annotation.EnableScheduling;
import tools.jackson.databind.json.JsonMapper;

@Slf4j
@Configuration
@EnableScheduling
public class OrderEventsConfig {

    /**
     * Subscribes this instance to the order-events channel and hands each event to the local SSE streams.
     * If Redis is down, the container keeps retrying in the background instead of failing startup.
     */
    @Bean
    RedisMessageListenerContainer orderEventListenerContainer(RedisConnectionFactory connectionFactory,
                                                              OrderEventStreams streams, JsonMapper jsonMapper) {
        RedisMessageListenerContainer container = new RedisMessageListenerContainer();
        container.setConnectionFactory(connectionFactory);
        container.addMessageListener((message, pattern) -> {
            try {
                streams.dispatch(jsonMapper.readValue(message.getBody(), OrderEvent.class));
            } catch (RuntimeException e) {
                log.warn("Ignoring unreadable order event: {}", e.toString());
            }
        }, new ChannelTopic(OrderEventBus.CHANNEL));
        return container;
    }
}
