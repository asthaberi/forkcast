package com.asthaberi.forkcast.search;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Bound from {@code forkcast.ai.search.*}.
 *
 * @param indexName     Redis search index; versioned so a format or model change builds a fresh index
 * @param keyPrefix     Redis key prefix of the indexed dish documents
 * @param minSimilarity cosine similarity (0..1) below which a dish isn't returned at all
 */
@ConfigurationProperties(prefix = "forkcast.ai.search")
public record SemanticSearchProperties(String indexName, String keyPrefix, int defaultLimit, int maxLimit,
                                       double minSimilarity) {
}
