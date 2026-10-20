package com.asthaberi.forkcast.search;

import static org.springframework.ai.vectorstore.redis.RedisVectorStore.MetadataField.numeric;
import static org.springframework.ai.vectorstore.redis.RedisVectorStore.MetadataField.tag;

import java.util.Map;

import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.embedding.EmbeddingModel;
import org.springframework.ai.vectorstore.redis.RedisVectorStore;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.stereotype.Component;
import redis.clients.jedis.RedisClient;

/**
 * The Redis vector index of dishes.
 *
 * Created lazily on first use rather than at startup: building it creates the Redis index and asks the
 * embedding model for its dimensions, and neither Redis nor Gemini being down should stop the application
 * from starting (a lesson from CI). If creation fails, the next use simply tries again.
 */
@Slf4j
@Component
public class DishVectorIndex {

    private final ObjectProvider<EmbeddingModel> embeddingModel;
    private final RedisClient redisClient;
    private final SemanticSearchProperties properties;

    private volatile RedisVectorStore store;

    public DishVectorIndex(ObjectProvider<EmbeddingModel> embeddingModel, RedisClient redisClient,
                           SemanticSearchProperties properties) {
        this.embeddingModel = embeddingModel;
        this.redisClient = redisClient;
        this.properties = properties;
    }

    /** False when no embedding model is configured (no API key). */
    public boolean isAvailable() {
        return embeddingModel.getIfAvailable() != null;
    }

    public RedisVectorStore store() {
        RedisVectorStore current = store;
        if (current == null) {
            synchronized (this) {
                current = store;
                if (current == null) {
                    current = create();
                    store = current;
                }
            }
        }
        return current;
    }

    /** Number of indexed dishes; 0 when the index doesn't exist yet. */
    public long documentCount() {
        try {
            Map<String, Object> info = redisClient.ftInfo(properties.indexName());
            return Long.parseLong(String.valueOf(info.get("num_docs")));
        } catch (RuntimeException e) {
            return 0;
        }
    }

    private RedisVectorStore create() {
        EmbeddingModel model = embeddingModel.getIfAvailable();
        if (model == null) {
            throw new IllegalStateException("No embedding model configured");
        }
        // Tag fields filter by exact value, numeric fields by range; everything else in the metadata is
        // stored with the document and returned with results, but not indexed.
        RedisVectorStore vectorStore = RedisVectorStore.builder(redisClient, model)
                .indexName(properties.indexName())
                .prefix(properties.keyPrefix())
                .metadataFields(tag("restaurantId"), tag("vegetarian"), tag("available"), tag("spiceLevel"),
                        numeric("price"))
                .initializeSchema(true)
                .build();
        vectorStore.afterPropertiesSet();
        log.info("Vector index {} ready", properties.indexName());
        return vectorStore;
    }
}
