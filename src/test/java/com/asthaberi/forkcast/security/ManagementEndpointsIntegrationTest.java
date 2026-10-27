package com.asthaberi.forkcast.security;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Instant;
import java.util.Set;

import com.asthaberi.forkcast.auth.TokenService;
import com.asthaberi.forkcast.support.TestContainers;
import com.asthaberi.forkcast.user.Role;
import com.asthaberi.forkcast.user.User;
import com.asthaberi.forkcast.user.UserRepository;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.micrometer.metrics.test.autoconfigure.AutoConfigureMetrics;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalManagementPort;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.mongodb.MongoDBContainer;

/**
 * Actuator runs on a separate, internal management port. Real HTTP on both ports (MockMvc can't reach the
 * management server): the API port only has /livez and /readyz, Prometheus scrapes the management port
 * without a token, and the detailed metrics there still need an admin.
 */
@Testcontainers(disabledWithoutDocker = true)
// Spring Boot tests leave metric exporters (Prometheus) off unless asked
@AutoConfigureMetrics
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = {"forkcast.seed.enabled=false", "spring.cache.type=none", "management.health.redis.enabled=false",
                "spring.data.redis.port=1"})
class ManagementEndpointsIntegrationTest {

    @Container
    @ServiceConnection
    static MongoDBContainer mongo = TestContainers.mongo();

    private final HttpClient http = HttpClient.newHttpClient();

    @LocalServerPort
    private int apiPort;
    @LocalManagementPort
    private int managementPort;
    @Autowired
    private TokenService tokenService;
    @Autowired
    private UserRepository userRepository;

    @Test
    void theApiPortHasHealthProbesButNoActuator() throws Exception {
        assertThat(managementPort).isNotEqualTo(apiPort);
        assertThat(get(apiPort, "/livez", null).statusCode()).isEqualTo(200);
        assertThat(get(apiPort, "/readyz", null).statusCode()).isEqualTo(200);
        assertThat(get(apiPort, "/actuator/prometheus", null).statusCode()).isIn(401, 404);
        assertThat(get(apiPort, "/actuator/prometheus", tokenFor(Role.ADMIN)).statusCode()).isEqualTo(404);
    }

    @Test
    void prometheusScrapesTheManagementPortWithoutAToken() throws Exception {
        // An API request first, so there are request metrics to scrape
        get(apiPort, "/api/v1/restaurants?latitude=26.21&longitude=78.18", null);

        HttpResponse<String> scrape = get(managementPort, "/actuator/prometheus", null);

        assertThat(scrape.statusCode()).isEqualTo(200);
        assertThat(scrape.body()).contains("http_server_requests_seconds", "forkcast_circuit_breaker_open");
        assertThat(get(managementPort, "/actuator/health", null).statusCode()).isEqualTo(200);
    }

    @Test
    void detailedMetricsOnTheManagementPortStillNeedAnAdmin() throws Exception {
        assertThat(get(managementPort, "/actuator/metrics", null).statusCode()).isEqualTo(401);
        assertThat(get(managementPort, "/actuator/metrics", tokenFor(Role.CUSTOMER)).statusCode()).isEqualTo(403);
        assertThat(get(managementPort, "/actuator/metrics", tokenFor(Role.ADMIN)).statusCode()).isEqualTo(200);
    }

    private String tokenFor(Role role) {
        User user = userRepository.save(User.create("Test", role.name().toLowerCase() + System.nanoTime() + "@example.com",
                "{noop}unused", Set.of(role), Instant.now()));
        return tokenService.issueAccessToken(user).accessToken();
    }

    private HttpResponse<String> get(int port, String path, String token) throws IOException, InterruptedException {
        HttpRequest.Builder request = HttpRequest.newBuilder(URI.create("http://localhost:" + port + path)).GET();
        if (token != null) {
            request.header("Authorization", "Bearer " + token);
        }
        return http.send(request.build(), HttpResponse.BodyHandlers.ofString());
    }
}
