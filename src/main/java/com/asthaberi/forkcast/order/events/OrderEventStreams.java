package com.asthaberi.forkcast.order.events;

import java.io.IOException;
import java.time.Duration;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArraySet;

import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.web.server.context.WebServerGracefulShutdownLifecycle;
import org.springframework.context.SmartLifecycle;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

/**
 * The open Server-Sent Events connections held by this app instance, grouped by what they follow:
 * one order (customer tracking page) or one restaurant (owner's live order queue).
 */
@Slf4j
@Component
public class OrderEventStreams implements SmartLifecycle {

    public static final String EVENT_NAME = "order-status";

    // A client reconnects after this; the stream's first event is always the current status, so nothing is lost
    private static final Duration STREAM_TIMEOUT = Duration.ofMinutes(30);
    private static final long RECONNECT_DELAY_MS = 3_000;

    private final Map<String, Set<SseEmitter>> subscribers = new ConcurrentHashMap<>();
    private volatile boolean running;

    public SseEmitter subscribeToOrder(String orderId) {
        return subscribe(orderKey(orderId));
    }

    public SseEmitter subscribeToRestaurant(String restaurantId) {
        return subscribe(restaurantKey(restaurantId));
    }

    /** Called for every event received from Redis, on every instance. */
    public void dispatch(OrderEvent event) {
        sendToAll(orderKey(event.orderId()), event, event.status().isTerminal());
        sendToAll(restaurantKey(event.restaurantId()), event, false);
    }

    /** Sends one event to a single emitter, e.g. the current status right after a client connects. */
    public static void send(SseEmitter emitter, OrderEvent event) throws IOException {
        emitter.send(SseEmitter.event()
                .name(EVENT_NAME)
                .id(event.orderId() + ":" + event.sequence())
                .reconnectTime(RECONNECT_DELAY_MS)
                .data(event));
    }

    /**
     * Proxies and load balancers often drop connections that are silent for 30-60 s. A comment line every
     * 20 s keeps idle streams open, and finds connections the client already abandoned so they can be freed.
     */
    @Scheduled(fixedRate = 20_000)
    public void heartbeat() {
        subscribers.forEach((key, emitters) -> emitters.forEach(emitter -> {
            try {
                emitter.send(SseEmitter.event().comment("keep-alive"));
            } catch (IOException | IllegalStateException e) {
                remove(key, emitter);
            }
        }));
    }

    /**
     * On shutdown, close every stream before the web server's graceful shutdown starts. Graceful shutdown
     * waits for in-flight requests, and an SSE stream never finishes by itself, so without this every
     * deploy would stall for the full shutdown timeout. Closed clients reconnect (to another instance
     * behind the load balancer) and receive the current status as their first event.
     */
    @Override
    public void stop() {
        running = false;
        log.info("Closing {} live order streams for shutdown", openStreams());
        subscribers.values().forEach(emitters -> emitters.forEach(SseEmitter::complete));
        subscribers.clear();
    }

    @Override
    public void start() {
        running = true;
    }

    @Override
    public boolean isRunning() {
        return running;
    }

    // Stops before WebServerGracefulShutdownLifecycle (higher phases stop first)
    @Override
    public int getPhase() {
        return WebServerGracefulShutdownLifecycle.SMART_LIFECYCLE_PHASE + 1;
    }

    public int openStreams() {
        return subscribers.values().stream().mapToInt(Set::size).sum();
    }

    private SseEmitter subscribe(String key) {
        SseEmitter emitter = new SseEmitter(STREAM_TIMEOUT.toMillis());
        subscribers.computeIfAbsent(key, k -> new CopyOnWriteArraySet<>()).add(emitter);
        emitter.onCompletion(() -> remove(key, emitter));
        emitter.onTimeout(() -> remove(key, emitter));
        emitter.onError(error -> remove(key, emitter));
        return emitter;
    }

    private void sendToAll(String key, OrderEvent event, boolean thenClose) {
        for (SseEmitter emitter : subscribers.getOrDefault(key, Set.of())) {
            try {
                send(emitter, event);
                if (thenClose) {
                    emitter.complete();
                }
            } catch (IOException | IllegalStateException e) {
                // The client went away; drop this connection
                remove(key, emitter);
            }
        }
    }

    private void remove(String key, SseEmitter emitter) {
        subscribers.computeIfPresent(key, (k, emitters) -> {
            emitters.remove(emitter);
            return emitters.isEmpty() ? null : emitters;
        });
    }

    private static String orderKey(String orderId) {
        return "order:" + orderId;
    }

    private static String restaurantKey(String restaurantId) {
        return "restaurant:" + restaurantId;
    }
}
