package com.asthaberi.forkcast.ratelimit;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

import com.asthaberi.forkcast.ratelimit.RateLimitProperties.Policy;
import com.asthaberi.forkcast.support.TestContainers;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.data.redis.connection.RedisStandaloneConfiguration;
import org.springframework.data.redis.connection.lettuce.LettuceClientConfiguration;
import org.springframework.data.redis.connection.lettuce.LettuceConnectionFactory;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

/** The Lua token bucket against a real Redis: burst, refill, atomicity across instances, failure handling. */
@Testcontainers(disabledWithoutDocker = true)
class RedisRateLimiterIntegrationTest {

    @Container
    static GenericContainer<?> redis = TestContainers.redis();

    private static final RateLimitProperties PROPERTIES = new RateLimitProperties(true, Map.of(
            "burst", new Policy(5, 5, Duration.ofMinutes(1)),
            "fast-refill", new Policy(2, 2, Duration.ofMillis(400)),
            "contended", new Policy(20, 1, Duration.ofHours(1))));

    private final List<LettuceConnectionFactory> factories = new ArrayList<>();
    private final SimpleMeterRegistry meters = new SimpleMeterRegistry();

    @AfterEach
    void closeConnections() {
        factories.forEach(LettuceConnectionFactory::destroy);
    }

    @Test
    void allowsTheBurstThenRejectsWithTimeUntilTheNextToken() {
        RateLimiter limiter = limiter(redis.getMappedPort(6379));
        String key = uniqueKey();

        for (int i = 4; i >= 0; i--) {
            RateLimitDecision decision = limiter.tryConsume("burst", key);
            assertThat(decision.allowed()).isTrue();
            assertThat(decision.remaining()).isEqualTo(i);
        }
        RateLimitDecision rejected = limiter.tryConsume("burst", key);

        assertThat(rejected.allowed()).isFalse();
        // 5 tokens per minute = one every 12 s
        assertThat(rejected.retryAfter()).isBetween(Duration.ofSeconds(11), Duration.ofSeconds(12));
        assertThat(limiter.tryConsume("burst", uniqueKey()).allowed()).as("other keys unaffected").isTrue();
    }

    @Test
    void refillsOverTime() throws InterruptedException {
        RateLimiter limiter = limiter(redis.getMappedPort(6379));
        String key = uniqueKey();
        limiter.tryConsume("fast-refill", key);
        limiter.tryConsume("fast-refill", key);
        assertThat(limiter.tryConsume("fast-refill", key).allowed()).isFalse();

        // 2 tokens per 400 ms = one every 200 ms
        Thread.sleep(250);

        assertThat(limiter.tryConsume("fast-refill", key).allowed()).isTrue();
        assertThat(limiter.tryConsume("fast-refill", key).allowed()).isFalse();
    }

    @Test
    void concurrentRequestsFromTwoAppInstancesNeverExceedTheLimit() throws Exception {
        // Two limiters with separate connections stand in for two app instances sharing one Redis
        List<RateLimiter> instances = List.of(limiter(redis.getMappedPort(6379)), limiter(redis.getMappedPort(6379)));
        String key = uniqueKey();
        int requests = 100;
        CyclicBarrier together = new CyclicBarrier(requests);

        int allowed = 0;
        try (ExecutorService pool = Executors.newFixedThreadPool(requests)) {
            List<Future<Boolean>> results = new ArrayList<>();
            for (int i = 0; i < requests; i++) {
                RateLimiter instance = instances.get(i % 2);
                results.add(pool.submit(() -> {
                    together.await();
                    return instance.tryConsume("contended", key).allowed();
                }));
            }
            for (Future<Boolean> result : results) {
                allowed += result.get() ? 1 : 0;
            }
        }

        assertThat(allowed).isEqualTo(20);
    }

    @Test
    void storesHashedKeysThatExpireWhenIdle() {
        StringRedisTemplate template = template(redis.getMappedPort(6379));
        new RedisRateLimiter(template, PROPERTIES, meters).tryConsume("burst", "astha@example.com");

        Set<String> keys = template.keys("forkcast:rate-limit:burst:*");
        assertThat(keys).hasSize(1).noneMatch(k -> k.contains("astha"));
        // Full again after 5 tokens at 12 s each = 60 s, plus 1 s margin
        assertThat(template.getExpire(keys.iterator().next())).isBetween(55L, 61L);
    }

    @Test
    void failsOpenWhenRedisIsUnreachableThenStopsTryingForAWhile() {
        long[] now = {0};
        // nothing listens on port 1
        RedisRateLimiter limiter = new RedisRateLimiter(template(1), PROPERTIES, meters, () -> now[0]);

        assertThat(limiter.tryConsume("burst", uniqueKey()).allowed()).isTrue();
        // Within the skip window: allowed at once, without waiting for another Redis timeout
        now[0] += Duration.ofSeconds(4).toNanos();
        long start = System.nanoTime();
        assertThat(limiter.tryConsume("burst", uniqueKey()).allowed()).isTrue();
        assertThat(Duration.ofNanos(System.nanoTime() - start)).isLessThan(Duration.ofMillis(50));
        // After the window it tries Redis again
        now[0] += Duration.ofSeconds(2).toNanos();
        assertThat(limiter.tryConsume("burst", uniqueKey()).allowed()).isTrue();

        assertThat(outcomes("error")).isEqualTo(2);
        assertThat(outcomes("skipped")).isEqualTo(1);
    }

    private double outcomes(String outcome) {
        return meters.counter("forkcast.rate_limit.decisions", "policy", "burst", "outcome", outcome).count();
    }

    private RateLimiter limiter(int port) {
        return new RedisRateLimiter(template(port), PROPERTIES, meters);
    }

    private StringRedisTemplate template(int port) {
        LettuceConnectionFactory factory = new LettuceConnectionFactory(
                new RedisStandaloneConfiguration(redis.getHost(), port),
                LettuceClientConfiguration.builder().commandTimeout(Duration.ofMillis(500)).build());
        factory.afterPropertiesSet();
        factory.start();
        factories.add(factory);
        return new StringRedisTemplate(factory);
    }

    private static String uniqueKey() {
        return "test:" + UUID.randomUUID();
    }
}
