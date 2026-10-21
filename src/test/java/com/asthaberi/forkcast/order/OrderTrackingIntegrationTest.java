package com.asthaberi.forkcast.order;

import static org.assertj.core.api.Assertions.assertThat;

import java.math.BigDecimal;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;

import com.asthaberi.forkcast.menu.MenuItem;
import com.asthaberi.forkcast.menu.MenuItemRepository;
import com.asthaberi.forkcast.menu.SpiceLevel;
import com.asthaberi.forkcast.restaurant.Restaurant;
import com.asthaberi.forkcast.restaurant.RestaurantRepository;
import com.asthaberi.forkcast.support.TestContainers;
import com.asthaberi.forkcast.user.Role;
import com.asthaberi.forkcast.user.User;
import com.asthaberi.forkcast.user.UserRepository;
import com.jayway.jsonpath.JsonPath;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.SpringBootTest.WebEnvironment;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.data.mongodb.core.geo.GeoJsonPoint;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.mongodb.MongoDBContainer;

/**
 * Live tracking end to end over real HTTP connections: a real server port, real SSE streams read line by
 * line, MongoDB for orders and Redis pub/sub carrying events from the request that changes a status to
 * the connection that's waiting for it.
 */
@Testcontainers(disabledWithoutDocker = true)
@SpringBootTest(webEnvironment = WebEnvironment.RANDOM_PORT,
        properties = {"forkcast.seed.enabled=false", "spring.cache.type=none", "forkcast.rate-limit.enabled=false"})
class OrderTrackingIntegrationTest {

    @Container
    @ServiceConnection
    static MongoDBContainer mongo = TestContainers.mongo();

    @Container
    @ServiceConnection(name = "redis")
    static GenericContainer<?> redis = TestContainers.redis();

    private static final String PASSWORD = "integration-password";
    private static final Duration WAIT = Duration.ofSeconds(10);

    @LocalServerPort
    private int port;
    @Autowired
    private UserRepository userRepository;
    @Autowired
    private RestaurantRepository restaurantRepository;
    @Autowired
    private MenuItemRepository menuItemRepository;
    @Autowired
    private OrderRepository orderRepository;
    @Autowired
    private PasswordEncoder passwordEncoder;

    private final HttpClient http = HttpClient.newHttpClient();
    private final List<CompletableFuture<?>> openStreams = new ArrayList<>();
    private String restaurantId;
    private String dosaId;
    private String customerToken;
    private String ownerToken;

    @BeforeEach
    void setUp() throws Exception {
        orderRepository.deleteAll();
        menuItemRepository.deleteAll();
        restaurantRepository.deleteAll();
        userRepository.deleteAll();

        User owner = save("owner@example.com", Role.RESTAURANT_OWNER);
        save("customer@example.com", Role.CUSTOMER);
        Restaurant restaurant = restaurantRepository.save(Restaurant.builder().name("Dosa Junction")
                .address("Patel Nagar").city("Gwalior").location(new GeoJsonPoint(78.1850, 26.2080))
                .cuisines(List.of("south indian")).opensAt(LocalTime.MIDNIGHT).closesAt(LocalTime.MIDNIGHT)
                .rating(4.2).ownerId(owner.getId()).build());
        restaurantId = restaurant.getId();
        dosaId = menuItemRepository.save(MenuItem.builder().restaurantId(restaurantId).name("Masala Dosa")
                .category("Dosas").price(new BigDecimal("129.00")).vegetarian(true).spiceLevel(SpiceLevel.MILD)
                .tags(List.of()).available(true).build()).getId();

        customerToken = login("customer@example.com");
        ownerToken = login("owner@example.com");
    }

    @AfterEach
    void closeStreams() {
        openStreams.forEach(stream -> stream.cancel(true));
    }

    @Test
    void customerWatchesTheirOrderFromPlacedToDeliveredAndRestaurantSeesItArrive() throws Exception {
        BlockingQueue<String> restaurantFeed = openStream("/api/v1/restaurants/" + restaurantId + "/orders/events", ownerToken);
        String orderId = placeOrder();

        assertThat(nextStatus(restaurantFeed)).isEqualTo("PLACED");

        BlockingQueue<String> tracking = openStream("/api/v1/orders/" + orderId + "/events", customerToken);
        assertThat(nextStatus(tracking)).as("current status sent on connect").isEqualTo("PLACED");

        for (String status : List.of("ACCEPTED", "PREPARING", "OUT_FOR_DELIVERY", "DELIVERED")) {
            assertThat(post("/api/v1/restaurants/" + restaurantId + "/orders/" + orderId + "/status",
                    "{\"status\":\"" + status + "\"}", ownerToken).statusCode()).isEqualTo(200);
            assertThat(nextStatus(tracking)).isEqualTo(status);
            assertThat(nextStatus(restaurantFeed)).isEqualTo(status);
        }
        // Delivered is final, so the server closes the customer's stream
        assertThat(tracking.poll(WAIT.toSeconds(), TimeUnit.SECONDS)).isEqualTo(END_OF_STREAM);
    }

    @Test
    void cancelAndAcceptAtTheSameMomentLeaveExactlyOneOutcome() throws Exception {
        for (int round = 0; round < 5; round++) {
            String orderId = placeOrder();
            CyclicBarrier together = new CyclicBarrier(2);
            CompletableFuture<Integer> cancel = CompletableFuture.supplyAsync(() -> {
                await(together);
                return post("/api/v1/orders/" + orderId + "/cancel", "", customerToken).statusCode();
            });
            CompletableFuture<Integer> accept = CompletableFuture.supplyAsync(() -> {
                await(together);
                return post("/api/v1/restaurants/" + restaurantId + "/orders/" + orderId + "/status",
                        "{\"status\":\"ACCEPTED\"}", ownerToken).statusCode();
            });

            List<Integer> statuses = List.of(cancel.get(), accept.get());
            Order stored = orderRepository.findById(orderId).orElseThrow();
            assertThat(statuses).containsExactlyInAnyOrder(200, 409);
            assertThat(stored.getStatus()).isEqualTo(cancel.get() == 200 ? OrderStatus.CANCELLED : OrderStatus.ACCEPTED);
            assertThat(stored.getStatusHistory()).hasSize(2);
        }
    }

    @Test
    void streamsAreOnlyForTheOrdersOwnerAndTheRestaurant() throws Exception {
        String orderId = placeOrder();
        save("other@example.com", Role.CUSTOMER);
        String otherCustomer = login("other@example.com");
        save("other-owner@example.com", Role.RESTAURANT_OWNER);
        String otherOwner = login("other-owner@example.com");

        assertThat(get("/api/v1/orders/" + orderId + "/events", null).statusCode()).isEqualTo(401);
        assertThat(get("/api/v1/orders/" + orderId + "/events", otherCustomer).statusCode()).isEqualTo(404);
        assertThat(get("/api/v1/restaurants/" + restaurantId + "/orders/events", otherOwner).statusCode()).isEqualTo(403);
        assertThat(get("/api/v1/restaurants/" + restaurantId + "/orders", null).statusCode()).isEqualTo(401);
    }

    // --- helpers ---------------------------------------------------------------------------------

    private static final String END_OF_STREAM = "<end of stream>";

    /** Opens an SSE connection and collects the "data:" payloads of its events in a queue. */
    private BlockingQueue<String> openStream(String path, String token) throws Exception {
        BlockingQueue<String> events = new LinkedBlockingQueue<>();
        HttpRequest request = HttpRequest.newBuilder(uri(path)).header("Authorization", "Bearer " + token)
                .header("Accept", "text/event-stream").GET().build();
        CompletableFuture<Void> stream = http.sendAsync(request, HttpResponse.BodyHandlers.ofLines())
                .thenAccept(response -> {
                    assertThat(response.statusCode()).isEqualTo(200);
                    response.body().filter(line -> line.startsWith("data:"))
                            .forEach(line -> events.add(line.substring(5)));
                    events.add(END_OF_STREAM);
                });
        openStreams.add(stream);
        return events;
    }

    private static String nextStatus(BlockingQueue<String> events) throws InterruptedException {
        String event = events.poll(WAIT.toSeconds(), TimeUnit.SECONDS);
        assertThat(event).as("an event within %s", WAIT).isNotNull().isNotEqualTo(END_OF_STREAM);
        return JsonPath.read(event, "$.status");
    }

    private String placeOrder() throws Exception {
        assertThat(post("/api/v1/cart/items", "{\"menuItemId\":\"" + dosaId + "\",\"quantity\":1}", customerToken)
                .statusCode()).isEqualTo(200);
        HttpResponse<String> response = http.send(HttpRequest.newBuilder(uri("/api/v1/orders"))
                .header("Authorization", "Bearer " + customerToken)
                .header("Idempotency-Key", UUID.randomUUID().toString())
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(
                        "{\"deliveryAddress\":{\"line\":\"Hostel 3\",\"latitude\":26.2120,\"longitude\":78.1830}}"))
                .build(), HttpResponse.BodyHandlers.ofString());
        assertThat(response.statusCode()).isEqualTo(201);
        return JsonPath.read(response.body(), "$.id");
    }

    private String login(String email) throws Exception {
        HttpResponse<String> response = post("/api/v1/auth/login",
                "{\"email\":\"" + email + "\",\"password\":\"" + PASSWORD + "\"}", null);
        assertThat(response.statusCode()).isEqualTo(200);
        return JsonPath.read(response.body(), "$.accessToken");
    }

    private HttpResponse<String> post(String path, String json, String token) {
        HttpRequest.Builder builder = HttpRequest.newBuilder(uri(path)).header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(json));
        return send(token == null ? builder : builder.header("Authorization", "Bearer " + token));
    }

    private HttpResponse<String> get(String path, String token) {
        HttpRequest.Builder builder = HttpRequest.newBuilder(uri(path)).timeout(Duration.ofSeconds(5)).GET();
        return send(token == null ? builder : builder.header("Authorization", "Bearer " + token));
    }

    private HttpResponse<String> send(HttpRequest.Builder builder) {
        try {
            return http.send(builder.build(), HttpResponse.BodyHandlers.ofString());
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    private URI uri(String path) {
        return URI.create("http://localhost:" + port + path);
    }

    private User save(String email, Role role) {
        return userRepository.save(User.create("Test", email, passwordEncoder.encode(PASSWORD), Set.of(role),
                Instant.now()));
    }

    private static void await(CyclicBarrier barrier) {
        try {
            barrier.await(5, TimeUnit.SECONDS);
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }
}
