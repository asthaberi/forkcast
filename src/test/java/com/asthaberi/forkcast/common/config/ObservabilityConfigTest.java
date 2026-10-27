package com.asthaberi.forkcast.common.config;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.util.Map;

import io.micrometer.observation.Observation;
import io.micrometer.observation.ObservationPredicate;
import io.micrometer.tracing.exporter.FinishedSpan;
import io.micrometer.tracing.exporter.SpanExportingPredicate;
import org.junit.jupiter.api.Test;
import org.springframework.http.server.observation.ServerRequestObservationContext;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;

class ObservabilityConfigTest {

    private final ObservabilityConfig config = new ObservabilityConfig();
    private final ObservationPredicate predicate = config.onlyRequestWork();

    @Test
    void tracesApiRequestsButNotHealthChecksOrScrapes() {
        assertThat(predicate.test("http.server.requests", request("/api/v1/restaurants"))).isTrue();
        assertThat(predicate.test("http.server.requests", request("/actuator/prometheus"))).isFalse();
        assertThat(predicate.test("http.server.requests", request("/readyz"))).isFalse();
        assertThat(predicate.test("http.server.requests", request("/livez"))).isFalse();
    }

    @Test
    void skipsScheduledTasksAndSecurityOutsideARequest() {
        assertThat(predicate.test("tasks.scheduled.execution", new Observation.Context())).isFalse();
        assertThat(predicate.test("spring.security.filterchains", new Observation.Context())).isFalse();

        Observation.Context withinRequest = new Observation.Context();
        withinRequest.setParentObservation(Observation.start("http.server.requests", () -> new Observation.Context(),
                io.micrometer.observation.ObservationRegistry.create()));
        assertThat(predicate.test("spring.security.filterchains", withinRequest)).isTrue();
        assertThat(predicate.test("mongodb.command", new Observation.Context())).isTrue();
    }

    @Test
    void redisSpansAreNotExported() {
        SpanExportingPredicate exportable = config.noRedisSpans();

        assertThat(exportable.isExportable(span(Map.of("db.system", "redis")))).isFalse();
        assertThat(exportable.isExportable(span(Map.of("db.system", "mongodb")))).isTrue();
        assertThat(exportable.isExportable(span(Map.of()))).isTrue();
    }

    private static ServerRequestObservationContext request(String path) {
        return new ServerRequestObservationContext(new MockHttpServletRequest("GET", path), new MockHttpServletResponse());
    }

    private static FinishedSpan span(Map<String, String> tags) {
        FinishedSpan span = mock(FinishedSpan.class);
        when(span.getTags()).thenReturn(tags);
        return span;
    }
}
