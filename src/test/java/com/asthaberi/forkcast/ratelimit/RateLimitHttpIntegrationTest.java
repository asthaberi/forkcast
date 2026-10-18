package com.asthaberi.forkcast.ratelimit;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.util.UUID;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.mongodb.MongoDBContainer;

/** The configured limits as clients see them: 429s, Retry-After and RateLimit headers. */
@Testcontainers(disabledWithoutDocker = true)
@SpringBootTest(properties = {"forkcast.seed.enabled=false", "spring.cache.type=none",
        "forkcast.rate-limit.policies.api.capacity=30", "forkcast.rate-limit.policies.api.refill-tokens=30"})
@AutoConfigureMockMvc
class RateLimitHttpIntegrationTest {

    @Container
    @ServiceConnection
    static MongoDBContainer mongo = new MongoDBContainer("mongo:8.0").withReplicaSet();

    @Container
    @ServiceConnection(name = "redis")
    static GenericContainer<?> redis = new GenericContainer<>("redis:8.2").withExposedPorts(6379);

    @Autowired
    private MockMvc mockMvc;

    @Test
    void loginIsLimitedPerIpAndAnswers429WithRetryAfter() throws Exception {
        String ip = "198.51.100." + (int) (Math.random() * 250);
        for (int i = 0; i < 10; i++) {
            // Different emails each time, so only the per-IP limit is in play
            mockMvc.perform(login(UUID.randomUUID() + "@example.com").with(fromIp(ip)))
                    .andExpect(status().isUnauthorized());
        }

        mockMvc.perform(login("anyone@example.com").with(fromIp(ip)))
                .andExpect(status().isTooManyRequests())
                .andExpect(header().exists("Retry-After"))
                .andExpect(jsonPath("$.code").value("RATE_LIMITED"))
                .andExpect(jsonPath("$.retryAfterSeconds").isNumber());

        mockMvc.perform(login("anyone@example.com").with(fromIp("192.0.2.1")))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void loginIsLimitedPerAccountEvenAcrossManyIps() throws Exception {
        String target = "victim-" + UUID.randomUUID() + "@example.com";
        for (int i = 0; i < 10; i++) {
            mockMvc.perform(login(target).with(fromIp("203.0.113." + i))).andExpect(status().isUnauthorized());
        }

        mockMvc.perform(login(target).with(fromIp("203.0.113.200")))
                .andExpect(status().isTooManyRequests());
    }

    @Test
    void everyApiResponseCarriesRateLimitHeadersAndTheApiLimitApplies() throws Exception {
        String ip = "100.64.0." + (int) (Math.random() * 250);
        mockMvc.perform(search().with(fromIp(ip)))
                .andExpect(status().isOk())
                .andExpect(header().string("RateLimit-Limit", "30"))
                .andExpect(header().string("RateLimit-Remaining", "29"));
        for (int i = 0; i < 29; i++) {
            mockMvc.perform(search().with(fromIp(ip))).andExpect(status().isOk());
        }

        mockMvc.perform(search().with(fromIp(ip)))
                .andExpect(status().isTooManyRequests())
                .andExpect(header().string("RateLimit-Remaining", "0"))
                .andExpect(header().exists("Retry-After"))
                .andExpect(jsonPath("$.title").value("Too many requests"));
        // Health checks aren't API traffic and stay reachable
        mockMvc.perform(get("/actuator/health").with(fromIp(ip))).andExpect(status().isOk());
    }

    private static MockHttpServletRequestBuilder login(String email) {
        return post("/api/v1/auth/login").contentType(MediaType.APPLICATION_JSON)
                .content("{\"email\":\"" + email + "\",\"password\":\"wrong-password\"}");
    }

    private static MockHttpServletRequestBuilder search() {
        return get("/api/v1/restaurants").param("latitude", "26.2124").param("longitude", "78.1772");
    }

    private static org.springframework.test.web.servlet.request.RequestPostProcessor fromIp(String ip) {
        return request -> {
            request.setRemoteAddr(ip);
            return request;
        };
    }
}
