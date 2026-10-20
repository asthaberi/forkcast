package com.asthaberi.forkcast.search;

import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.ArrayList;
import java.util.Base64;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.zip.GZIPInputStream;

import org.springframework.ai.document.Document;
import org.springframework.ai.embedding.Embedding;
import org.springframework.ai.embedding.EmbeddingModel;
import org.springframework.ai.embedding.EmbeddingRequest;
import org.springframework.ai.embedding.EmbeddingResponse;
import org.springframework.core.io.ClassPathResource;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * Real Gemini embeddings of the 62 seeded dishes, recorded from a live index (gemini-embedding-001,
 * RETRIEVAL_DOCUMENT, 768 dimensions, stored as float16). Lets tests measure recommendation quality on the
 * real geometry of the embedding space, in CI, without an API key.
 *
 * Looked up by the exact text that's embedded, so if DishDocuments changes that text, tests fail with a clear
 * message: re-export the fixture from a live index.
 */
public class GeminiFixtureEmbeddingModel implements EmbeddingModel {

    static final String FIXTURE = "embeddings/gemini-dishes-768.json.gz";

    private final Map<String, float[]> vectors = load();

    @Override
    public EmbeddingResponse call(EmbeddingRequest request) {
        List<Embedding> embeddings = new ArrayList<>();
        for (int i = 0; i < request.getInstructions().size(); i++) {
            embeddings.add(new Embedding(vector(request.getInstructions().get(i)), i));
        }
        return new EmbeddingResponse(embeddings);
    }

    @Override
    public float[] embed(Document document) {
        return vector(document.getText());
    }

    @Override
    public int dimensions() {
        return 768;
    }

    private float[] vector(String text) {
        float[] vector = vectors.get(text);
        if (vector == null) {
            throw new IllegalStateException("No recorded Gemini vector for \"" + text + "\". The embedded text or "
                    + "the seed data changed; re-export " + FIXTURE + " from a live index.");
        }
        return vector;
    }

    private static Map<String, float[]> load() {
        try (InputStream in = new GZIPInputStream(new ClassPathResource(FIXTURE).getInputStream())) {
            JsonNode root = JsonMapper.builder().build().readTree(in);
            Map<String, float[]> vectors = new HashMap<>();
            root.get("vectors").properties().forEach(entry -> {
                ByteBuffer bytes = ByteBuffer.wrap(Base64.getDecoder().decode(entry.getValue().asString()))
                        .order(ByteOrder.LITTLE_ENDIAN);
                float[] vector = new float[bytes.remaining() / Short.BYTES];
                for (int i = 0; i < vector.length; i++) {
                    vector[i] = Float.float16ToFloat(bytes.getShort());
                }
                vectors.put(entry.getKey(), vector);
            });
            return vectors;
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }
}
