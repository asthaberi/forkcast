package com.asthaberi.forkcast.search;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Bound from {@code forkcast.ai.gemini.*}.
 *
 * @param apiKey    Gemini Developer API key; blank means the AI features are switched off
 * @param chatModel pinned model version (not a "-latest" alias), so behaviour can't change underneath us
 */
@ConfigurationProperties(prefix = "forkcast.ai.gemini")
public record GeminiProperties(String apiKey, String embeddingModel, int dimensions, String chatModel) {

    public boolean configured() {
        return apiKey != null && !apiKey.isBlank();
    }
}
