package com.asthaberi.forkcast.order.events;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;
import tools.jackson.databind.json.JsonMapper;

/**
 * Broadcasts order events over Redis pub/sub instead of delivering them in memory.
 *
 * A live-tracking connection is held open by whichever app instance the client happened to connect to,
 * while the status change may be handled by a different instance behind the load balancer. Publishing to
 * Redis lets every instance hear every event and forward it to the connections it holds.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class OrderEventBus {

    public static final String CHANNEL = "forkcast:order-events";

    private final StringRedisTemplate redisTemplate;
    private final JsonMapper jsonMapper;

    /**
     * Call only after the change is committed, so a rolled-back change is never announced. If Redis is
     * unavailable the event is dropped and logged: the database is already correct, and a reconnecting
     * client gets the current status as its first event.
     */
    public void publish(OrderEvent event) {
        try {
            redisTemplate.convertAndSend(CHANNEL, jsonMapper.writeValueAsString(event));
        } catch (RuntimeException e) {
            log.warn("Could not publish {} for order {}: {}", event.status(), event.orderId(), e.toString());
        }
    }
}
