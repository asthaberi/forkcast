package com.asthaberi.forkcast.search;

import com.google.genai.Client;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.observation.ObservationRegistry;
import org.springframework.ai.chat.observation.ChatModelMeterObservationHandler;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.embedding.EmbeddingModel;
import org.springframework.ai.google.genai.GoogleGenAiChatModel;
import org.springframework.ai.google.genai.GoogleGenAiChatOptions;
import org.springframework.ai.google.genai.common.GoogleGenAiThinkingLevel;
import org.springframework.ai.google.genai.embedding.GoogleGenAiEmbeddingConnectionDetails;
import org.springframework.ai.google.genai.text.GoogleGenAiTextEmbeddingModel;
import org.springframework.ai.google.genai.text.GoogleGenAiTextEmbeddingOptions;
import org.springframework.ai.google.genai.text.GoogleGenAiTextEmbeddingOptions.TaskType;
import org.springframework.ai.retry.RetryUtils;
import org.springframework.boot.autoconfigure.condition.ConditionalOnExpression;
import org.springframework.boot.data.redis.autoconfigure.DataRedisConnectionDetails;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.annotation.EnableAsync;
import redis.clients.jedis.ConnectionPoolConfig;
import redis.clients.jedis.RedisClient;

/**
 * The AI building blocks. The Gemini embedding model only exists when an API key is configured, so the
 * application (and CI, which has no key) starts and works without one; semantic search then reports
 * itself unavailable instead of breaking startup.
 */
@Configuration
@EnableAsync
public class AiConfig {

    /** Pooled Redis connections for vector search and dish-vector reads (load-tested; see docs/performance.md). */
    static final int VECTOR_STORE_CONNECTIONS = 32;

    @Bean
    @ConditionalOnExpression("!'${forkcast.ai.gemini.api-key:}'.isBlank()")
    EmbeddingModel dishEmbeddingModel(GeminiProperties gemini, ObservationRegistry observations) {
        GoogleGenAiEmbeddingConnectionDetails connection = GoogleGenAiEmbeddingConnectionDetails.builder()
                .apiKey(gemini.apiKey())
                .build();
        // Same retries as the shorter constructor, plus observations: every Gemini call becomes a timed metric
        return new RetrievalEmbeddingModel(
                new GoogleGenAiTextEmbeddingModel(connection, options(gemini, TaskType.RETRIEVAL_DOCUMENT),
                        RetryUtils.DEFAULT_RETRY_TEMPLATE, observations),
                new GoogleGenAiTextEmbeddingModel(connection, options(gemini, TaskType.RETRIEVAL_QUERY),
                        RetryUtils.DEFAULT_RETRY_TEMPLATE, observations),
                gemini.dimensions());
    }

    /**
     * Gemini chat model for writing review summaries. Low temperature: summaries should be consistent and
     * stick to what reviewers said, not be creative. JSON output so the answer maps onto a Java record.
     * Thinking is kept to a minimum: Gemini counts its hidden reasoning tokens against maxOutputTokens, which
     * cut the JSON off mid-sentence with Flash (979 thinking tokens of a 1024 budget), and summarising
     * doesn't need it.
     */
    @Bean
    @ConditionalOnExpression("!'${forkcast.ai.gemini.api-key:}'.isBlank()")
    ChatModel reviewChatModel(GeminiProperties gemini, ObservationRegistry observations) {
        return GoogleGenAiChatModel.builder()
                .observationRegistry(observations)
                .genAiClient(Client.builder().apiKey(gemini.apiKey()).build())
                .options(GoogleGenAiChatOptions.builder()
                        .model(gemini.chatModel())
                        .temperature(0.2)
                        .maxOutputTokens(2048)
                        .thinkingLevel(GoogleGenAiThinkingLevel.MINIMAL)
                        .responseMimeType("application/json")
                        .build())
                .build();
    }

    /**
     * Turns chat observations into token-usage counters (gen_ai.client.token.usage, by model and input/output),
     * next to the call-duration timer (gen_ai.client.operation) that every observation already produces.
     * Spring AI's starters would register this; the plain libraries used here don't.
     */
    @Bean
    ChatModelMeterObservationHandler chatModelTokenMetrics(MeterRegistry meterRegistry) {
        return new ChatModelMeterObservationHandler(meterRegistry);
    }

    /**
     * Spring AI's Redis vector store talks to Redis through Jedis; this client connects lazily. It takes the
     * address from Spring Boot's connection details rather than spring.data.redis.* properties, so it follows
     * the same Redis the rest of the app uses, including one supplied by Testcontainers or Docker Compose.
     */
    @Bean(destroyMethod = "close")
    RedisClient vectorStoreRedisClient(DataRedisConnectionDetails redis) {
        // Jedis allows only 8 pooled connections by default; recommendations make 3 vector calls per request
        ConnectionPoolConfig pool = new ConnectionPoolConfig();
        pool.setMaxTotal(VECTOR_STORE_CONNECTIONS);
        pool.setMaxIdle(VECTOR_STORE_CONNECTIONS);
        return RedisClient.builder()
                .hostAndPort(redis.getStandalone().getHost(), redis.getStandalone().getPort())
                .poolConfig(pool)
                .build();
    }

    private static GoogleGenAiTextEmbeddingOptions options(GeminiProperties gemini, TaskType taskType) {
        return GoogleGenAiTextEmbeddingOptions.builder()
                .model(gemini.embeddingModel())
                .dimensions(gemini.dimensions())
                .taskType(taskType)
                .build();
    }
}
