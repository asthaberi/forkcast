package com.asthaberi.forkcast.order.events;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Duration;
import java.time.Instant;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;

import com.asthaberi.forkcast.order.OrderStatus;
import io.lettuce.core.ClientOptions;
import io.lettuce.core.SocketOptions;
import org.junit.jupiter.api.Test;
import org.springframework.data.redis.connection.RedisStandaloneConfiguration;
import org.springframework.data.redis.connection.lettuce.LettuceClientConfiguration;
import org.springframework.data.redis.connection.lettuce.LettuceConnectionFactory;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import tools.jackson.databind.json.JsonMapper;

/**
 * Redis is unavailable when the app starts and comes back later: startup must not fail or wait, and
 * live order events must start flowing once Redis is reachable. (CI caught the earlier version failing
 * application startup whenever Redis was down.)
 */
@Testcontainers(disabledWithoutDocker = true)
class OrderEventSubscriberIntegrationTest {

    @Container
    static GenericContainer<?> redis = new GenericContainer<>("redis:8.2").withExposedPorts(6379);

    private final JsonMapper jsonMapper = JsonMapper.builder().build();

    @Test
    void startsWhileRedisIsDownAndSubscribesOnceItIsBack() throws Exception {
        // A paused container accepts no commands: to the app, Redis is unreachable
        redis.getDockerClient().pauseContainerCmd(redis.getContainerId()).exec();
        LettuceConnectionFactory factory = connectionFactory();
        CapturingStreams streams = new CapturingStreams();
        OrderEventSubscriber subscriber = new OrderEventSubscriber(factory, streams, jsonMapper);
        try {
            long start = System.nanoTime();
            subscriber.start();
            assertThat(Duration.ofNanos(System.nanoTime() - start)).as("start() must not wait for Redis")
                    .isLessThan(Duration.ofMillis(200));
            Thread.sleep(1_500);
            assertThat(subscriber.isSubscribed()).isFalse();

            redis.getDockerClient().unpauseContainerCmd(redis.getContainerId()).exec();
            long deadline = System.nanoTime() + Duration.ofSeconds(40).toNanos();
            while (!subscriber.isSubscribed() && System.nanoTime() < deadline) {
                Thread.sleep(100);
            }
            assertThat(subscriber.isSubscribed()).as("subscribed after Redis came back").isTrue();

            OrderEvent event = new OrderEvent("o1", "r1", OrderStatus.ACCEPTED, Instant.parse("2026-10-19T06:00:00Z"), 2);
            new StringRedisTemplate(factory).convertAndSend(OrderEventBus.CHANNEL, jsonMapper.writeValueAsString(event));

            assertThat(streams.received.poll(5, TimeUnit.SECONDS)).isEqualTo(event);
        } finally {
            subscriber.stop();
            factory.destroy();
        }
    }

    private static LettuceConnectionFactory connectionFactory() {
        LettuceClientConfiguration client = LettuceClientConfiguration.builder()
                .commandTimeout(Duration.ofMillis(500))
                .clientOptions(ClientOptions.builder()
                        .socketOptions(SocketOptions.builder().connectTimeout(Duration.ofMillis(500)).build())
                        .build())
                .build();
        LettuceConnectionFactory factory = new LettuceConnectionFactory(
                new RedisStandaloneConfiguration(redis.getHost(), redis.getMappedPort(6379)), client);
        factory.afterPropertiesSet();
        factory.start();
        return factory;
    }

    /** Records dispatched events instead of writing them to SSE connections. */
    private static class CapturingStreams extends OrderEventStreams {

        final BlockingQueue<OrderEvent> received = new LinkedBlockingQueue<>();

        @Override
        public void dispatch(OrderEvent event) {
            received.add(event);
        }
    }
}
