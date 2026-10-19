package com.asthaberi.forkcast.order.events;

import java.time.Duration;

import lombok.extern.slf4j.Slf4j;
import org.springframework.context.SmartLifecycle;
import org.springframework.data.redis.connection.RedisConnectionFactory;
import org.springframework.data.redis.listener.ChannelTopic;
import org.springframework.data.redis.listener.RedisMessageListenerContainer;
import org.springframework.stereotype.Component;
import tools.jackson.databind.json.JsonMapper;

/**
 * Subscribes this instance to the order-events channel and hands each event to the local SSE streams.
 *
 * The subscription never blocks or fails application startup. RedisMessageListenerContainer only
 * reconnects by itself once it's running; if Redis is down during its very first subscribe, start()
 * throws and would take the whole application down with it. So the first subscribe happens on a
 * background virtual thread that retries with exponential backoff, using a fresh container per attempt.
 * Once subscribed, the container's own recovery handles later disconnects.
 */
@Slf4j
@Component
public class OrderEventSubscriber implements SmartLifecycle {

    static final Duration FIRST_RETRY_DELAY = Duration.ofSeconds(1);
    static final Duration MAX_RETRY_DELAY = Duration.ofSeconds(30);

    private final RedisConnectionFactory connectionFactory;
    private final OrderEventStreams streams;
    private final JsonMapper jsonMapper;

    private volatile boolean running;
    private volatile RedisMessageListenerContainer container;
    private Thread subscriber;

    public OrderEventSubscriber(RedisConnectionFactory connectionFactory, OrderEventStreams streams,
                                JsonMapper jsonMapper) {
        this.connectionFactory = connectionFactory;
        this.streams = streams;
        this.jsonMapper = jsonMapper;
    }

    @Override
    public void start() {
        running = true;
        subscriber = Thread.ofVirtual().name("order-events-subscriber").start(this::subscribeWithRetry);
    }

    @Override
    public void stop() {
        running = false;
        if (subscriber != null) {
            subscriber.interrupt();
        }
        RedisMessageListenerContainer current = container;
        if (current != null) {
            current.stop();
            destroyQuietly(current);
        }
    }

    @Override
    public boolean isRunning() {
        return running;
    }

    /** True once events from Redis are being received. */
    public boolean isSubscribed() {
        RedisMessageListenerContainer current = container;
        return current != null && current.isRunning();
    }

    private void subscribeWithRetry() {
        Duration delay = FIRST_RETRY_DELAY;
        boolean warned = false;
        while (running) {
            RedisMessageListenerContainer candidate = newContainer();
            try {
                candidate.start();
                container = candidate;
                log.info("Subscribed to {} for live order events", OrderEventBus.CHANNEL);
                return;
            } catch (RuntimeException e) {
                destroyQuietly(candidate);
                if (!warned) {
                    log.warn("Can't subscribe to live order events yet (Redis unavailable); the API works, and "
                            + "tracking updates start once Redis is reachable. Retrying in the background: {}",
                            e.toString());
                    warned = true;
                }
            }
            try {
                Thread.sleep(delay);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return;
            }
            delay = delay.multipliedBy(2).compareTo(MAX_RETRY_DELAY) > 0 ? MAX_RETRY_DELAY : delay.multipliedBy(2);
        }
    }

    private RedisMessageListenerContainer newContainer() {
        RedisMessageListenerContainer candidate = new RedisMessageListenerContainer();
        candidate.setConnectionFactory(connectionFactory);
        candidate.addMessageListener((message, pattern) -> {
            try {
                streams.dispatch(jsonMapper.readValue(message.getBody(), OrderEvent.class));
            } catch (RuntimeException e) {
                log.warn("Ignoring unreadable order event: {}", e.toString());
            }
        }, new ChannelTopic(OrderEventBus.CHANNEL));
        candidate.afterPropertiesSet();
        return candidate;
    }

    private static void destroyQuietly(RedisMessageListenerContainer candidate) {
        try {
            candidate.destroy();
        } catch (Exception e) {
            // Nothing useful to do; the next attempt uses a new container
        }
    }
}
