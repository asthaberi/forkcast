package com.asthaberi.forkcast.common.config;

import io.micrometer.observation.ObservationPredicate;
import io.micrometer.observation.ObservationRegistry;
import io.micrometer.tracing.exporter.SpanExportingPredicate;
import org.springframework.boot.mongodb.autoconfigure.MongoClientSettingsBuilderCustomizer;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.data.mongodb.observability.ContextProviderFactory;
import org.springframework.data.mongodb.observability.MongoObservationCommandListener;
import org.springframework.http.server.observation.ServerRequestObservationContext;

/**
 * Tracing setup on top of Spring Boot's defaults (exported over OTLP when OTLP_TRACES_ENDPOINT is set).
 */
@Configuration
public class ObservabilityConfig {

    /**
     * MongoDB commands as spans inside the request's trace. Spring Boot instruments the driver for metrics
     * only; the context provider carries the current trace into the driver so the spans get the right parent.
     */
    @Bean
    MongoClientSettingsBuilderCustomizer mongoCommandTracing(ObservationRegistry observations) {
        return settings -> settings
                .contextProvider(ContextProviderFactory.create(observations))
                .addCommandListener(new MongoObservationCommandListener(observations));
    }

    /**
     * Keeps traces about user requests. Without this, most traces are operational noise: Prometheus scrapes
     * every 5 s, health checks, the live-tracking heartbeat task, and Spring Security work outside any request
     * (on the management port), each showing up as its own root trace. A disabled observation also records no
     * metrics, which is fine for these.
     */
    @Bean
    ObservationPredicate onlyRequestWork() {
        return (name, context) -> {
            if (context instanceof ServerRequestObservationContext request) {
                String path = request.getCarrier().getRequestURI();
                return !(path.startsWith("/actuator") || path.equals("/livez") || path.equals("/readyz"));
            }
            if (name.equals("tasks.scheduled.execution")) {
                return false;
            }
            return !(name.startsWith("spring.security.") && context.getParentObservation() == null);
        };
    }

    /**
     * Redis commands stay out of exported traces but still produce metrics (the Redis latency panel). Lettuce
     * runs commands on its own I/O threads, so its spans never join the request's trace; exported, each would
     * be a separate one-span trace. Disabling the observation instead would also have removed the metrics.
     */
    @Bean
    SpanExportingPredicate noRedisSpans() {
        return span -> !"redis".equals(span.getTags().get("db.system"));
    }
}
