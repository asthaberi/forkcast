package com.asthaberi.forkcast.search;

import org.springframework.ai.document.Document;
import org.springframework.ai.embedding.EmbeddingModel;
import org.springframework.ai.embedding.EmbeddingRequest;
import org.springframework.ai.embedding.EmbeddingResponse;

/**
 * Embeds dishes and search queries differently. Gemini's embeddings are asymmetric: a document embedded
 * as RETRIEVAL_DOCUMENT and a query embedded as RETRIEVAL_QUERY land closer together than if both used
 * the same task type, which is what "find dishes for this request" needs.
 *
 * The vector store embeds documents through {@link #call} and the search text through
 * {@link #embed(String)}, so each path is routed to the matching model.
 *
 * It also reports the configured vector size. The vector store sizes its Redis index from
 * {@link #dimensions()}, and Spring AI's Gemini model returns the model's default (3072) there even when
 * configured for 768, so the index rejected every 768-dimension vector. Found on the first live run.
 */
public class RetrievalEmbeddingModel implements EmbeddingModel {

    private final EmbeddingModel documentModel;
    private final EmbeddingModel queryModel;
    private final int dimensions;

    public RetrievalEmbeddingModel(EmbeddingModel documentModel, EmbeddingModel queryModel, int dimensions) {
        this.documentModel = documentModel;
        this.queryModel = queryModel;
        this.dimensions = dimensions;
    }

    @Override
    public EmbeddingResponse call(EmbeddingRequest request) {
        return documentModel.call(request);
    }

    @Override
    public float[] embed(Document document) {
        return documentModel.embed(document);
    }

    @Override
    public float[] embed(String query) {
        return queryModel.embed(query);
    }

    @Override
    public int dimensions() {
        return dimensions;
    }
}
