package com.asthaberi.forkcast.search;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

import org.springframework.ai.document.Document;
import org.springframework.ai.embedding.Embedding;
import org.springframework.ai.embedding.EmbeddingModel;
import org.springframework.ai.embedding.EmbeddingRequest;
import org.springframework.ai.embedding.EmbeddingResponse;

/**
 * A deterministic stand-in for Gemini in tests: hashes each word into one of 256 buckets and normalises
 * the vector, so texts that share words are similar. It understands no meaning, but it exercises the real
 * pipeline (indexing, Redis KNN search, filters) without a network call or API key.
 */
public class KeywordEmbeddingModel implements EmbeddingModel {

    static final int DIMENSIONS = 256;

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
    public float[] embed(String text) {
        return vector(text);
    }

    @Override
    public int dimensions() {
        return DIMENSIONS;
    }

    static float[] vector(String text) {
        float[] vector = new float[DIMENSIONS];
        for (String word : text.toLowerCase(Locale.ROOT).split("[^a-z0-9]+")) {
            if (word.length() > 2) {
                String stem = word.endsWith("s") ? word.substring(0, word.length() - 1) : word;
                vector[Math.floorMod(stem.hashCode(), DIMENSIONS)] += 1;
            }
        }
        double norm = 0;
        for (float value : vector) {
            norm += value * value;
        }
        if (norm > 0) {
            for (int i = 0; i < vector.length; i++) {
                vector[i] /= (float) Math.sqrt(norm);
            }
        }
        return vector;
    }
}
