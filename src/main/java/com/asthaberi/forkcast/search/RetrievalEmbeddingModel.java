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
 */
public class RetrievalEmbeddingModel implements EmbeddingModel {

    private final EmbeddingModel documentModel;
    private final EmbeddingModel queryModel;

    public RetrievalEmbeddingModel(EmbeddingModel documentModel, EmbeddingModel queryModel) {
        this.documentModel = documentModel;
        this.queryModel = queryModel;
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
        return documentModel.dimensions();
    }
}
