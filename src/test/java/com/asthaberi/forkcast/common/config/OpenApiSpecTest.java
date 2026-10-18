package com.asthaberi.forkcast.common.config;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;

import com.jayway.jsonpath.DocumentContext;
import com.jayway.jsonpath.JsonPath;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.test.web.servlet.MockMvc;

/**
 * Checks the generated OpenAPI contract and writes it to target/openapi.json (CI publishes it).
 * Generating the spec only needs the controllers, so this runs without a database.
 */
@SpringBootTest(properties = {"forkcast.seed.enabled=false", "spring.data.mongodb.auto-index-creation=false"})
@AutoConfigureMockMvc
class OpenApiSpecTest {

    @Autowired
    private MockMvc mockMvc;

    private DocumentContext spec;

    @BeforeEach
    void loadSpec() throws Exception {
        String json = mockMvc.perform(get("/v3/api-docs")).andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();
        Files.writeString(Path.of("target", "openapi.json"), json);
        spec = JsonPath.parse(json);
    }

    @Test
    void docsArePublicAndSwaggerUiIsServed() throws Exception {
        mockMvc.perform(get("/swagger-ui/index.html")).andExpect(status().isOk());
    }

    @Test
    void describesJwtBearerAuthAndMarksPublicEndpoints() {
        assertThat(spec.read("$.components.securitySchemes.bearer-jwt.scheme", String.class)).isEqualTo("bearer");
        assertThat(spec.read("$.paths['/api/v1/restaurants'].get.security", List.class)).isEmpty();
        assertThat(spec.read("$.paths['/api/v1/auth/login'].post.security", List.class)).isEmpty();
        // Not overridden, so the global bearer requirement applies
        assertThat(spec.read("$.paths['/api/v1/cart'].get", Map.class)).doesNotContainKey("security");
        assertThat(spec.read("$.paths['/api/v1/cart'].get.responses", Map.class)).containsKeys("401", "403");
    }

    @Test
    void documentsRealStatusCodesAndProblemDetailErrors() {
        Map<String, Object> placeOrder = spec.read("$.paths['/api/v1/orders'].post.responses");
        assertThat(placeOrder).containsKeys("201", "200", "409", "422", "429");
        assertThat(spec.read("$.paths['/api/v1/orders'].post.responses.409.content", Map.class))
                .containsOnlyKeys("application/problem+json");
        assertThat(spec.read("$.paths['/api/v1/auth/login'].post.responses.401.description", String.class))
                .contains("INVALID_CREDENTIALS");
        assertThat(spec.read("$.paths['/api/v1/cart'].delete.responses", Map.class))
                .containsKey("204").doesNotContainKey("200");
        assertThat(spec.read("$.paths['/api/v1/orders/{orderId}/events'].get.responses.200.content", Map.class))
                .containsKey("text/event-stream");
    }

    @Test
    void everyOperationHasATagAndSummaryAndOnlyApiPathsAreIncluded() {
        Map<String, Map<String, Map<String, Object>>> paths = spec.read("$.paths");

        assertThat(paths.keySet()).allMatch(path -> path.startsWith("/api/v1/"));
        paths.forEach((path, operations) -> operations.forEach((method, operation) -> {
            assertThat(operation).as("%s %s", method, path).containsKeys("tags", "summary");
        }));
        assertThat(paths).hasSizeGreaterThanOrEqualTo(17);
    }
}
