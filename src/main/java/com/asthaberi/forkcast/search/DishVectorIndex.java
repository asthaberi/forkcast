package com.asthaberi.forkcast.search;

import static org.springframework.ai.vectorstore.redis.RedisVectorStore.MetadataField.numeric;
import static org.springframework.ai.vectorstore.redis.RedisVectorStore.MetadataField.tag;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.embedding.EmbeddingModel;
import org.springframework.ai.vectorstore.redis.RedisVectorStore;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.stereotype.Component;
import org.json.JSONArray;
import redis.clients.jedis.RedisClient;
import redis.clients.jedis.args.SortingOrder;
import redis.clients.jedis.json.Path2;
import redis.clients.jedis.search.FTSearchParams;

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

    /**
     * Documents Redis stored but couldn't index (e.g. a vector of the wrong size). Redis doesn't reject
     * these writes, so without checking this number such failures go unnoticed.
     */
    public long indexingFailures() {
        try {
            Map<String, Object> info = redisClient.ftInfo(properties.indexName());
            return Long.parseLong(String.valueOf(info.get("hash_indexing_failures")));
        } catch (RuntimeException e) {
            return 0;
        }
    }

    /**
     * The stored embeddings of these dishes, by menu item id. Reads vectors already in Redis; nothing is sent
     * to Gemini. Dishes that aren't indexed are left out.
     */
    public Map<String, float[]> vectorsOf(Collection<String> menuItemIds) {
        List<String> ids = List.copyOf(menuItemIds);
        Map<String, float[]> vectors = new HashMap<>();
        if (ids.isEmpty()) {
            return vectors;
        }
        List<JSONArray> results = redisClient.jsonMGet(Path2.of("$.embedding"),
                ids.stream().map(id -> properties.keyPrefix() + id).toArray(String[]::new));
        for (int i = 0; i < ids.size(); i++) {
            JSONArray matches = results.get(i);
            if (matches != null && !matches.isEmpty()) {
                JSONArray values = matches.getJSONArray(0);
                float[] vector = new float[values.length()];
                for (int d = 0; d < vector.length; d++) {
                    vector[d] = values.getFloat(d);
                }
                vectors.put(ids.get(i), vector);
            }
        }
        return vectors;
    }

    /**
     * The k available dishes nearest to a vector, among the given restaurants: a KNN query in Redis with the
     * filters applied inside it. Unlike {@code store().similaritySearch}, this starts from a vector rather than
     * a text, so no embedding call is needed.
     */
    public List<Neighbour> nearest(float[] vector, int k, Collection<String> restaurantIds, boolean vegetarianOnly) {
        if (restaurantIds.isEmpty()) {
            return List.of();
        }
        String filter = "@available:{true} @restaurantId:{" + String.join(" | ", restaurantIds) + "}"
                + (vegetarianOnly ? " @vegetarian:{true}" : "");
        FTSearchParams params = FTSearchParams.searchParams()
                .addParam("vector", toBytes(vector))
                .returnFields("distance")
                .sortBy("distance", SortingOrder.ASC)
                .limit(0, k)
                .dialect(2);
        List<Neighbour> neighbours = new ArrayList<>();
        for (var document : redisClient.ftSearch(properties.indexName(),
                "(" + filter + ")=>[KNN " + k + " @embedding $vector AS distance]", params).getDocuments()) {
            // Redis reports cosine distance (1 - cosine similarity)
            double similarity = 1 - Double.parseDouble(document.getString("distance"));
            neighbours.add(new Neighbour(document.getId().substring(properties.keyPrefix().length()), similarity));
        }
        return neighbours;
    }

    /** @param similarity cosine similarity, -1..1 */
    public record Neighbour(String menuItemId, double similarity) {
    }

    // Redis vector query parameters are raw little-endian float32 bytes
    private static byte[] toBytes(float[] vector) {
        ByteBuffer buffer = ByteBuffer.allocate(vector.length * Float.BYTES).order(ByteOrder.LITTLE_ENDIAN);
        for (float value : vector) {
            buffer.putFloat(value);
        }
        return buffer.array();
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
        log.info("Vector index {} ready ({} dimensions)", properties.indexName(), model.dimensions());
        return vectorStore;
    }
}
