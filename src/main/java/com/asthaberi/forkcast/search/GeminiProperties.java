package com.asthaberi.forkcast.search;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Bound from {@code forkcast.ai.gemini.*}.
 *
 * @param apiKey Gemini Developer API key; blank means the AI features are switched off
 */
@ConfigurationProperties(prefix = "forkcast.ai.gemini")
public record GeminiProperties(String apiKey, String embeddingModel, int dimensions) {

    public boolean configured() {
        return apiKey != null && !apiKey.isBlank();
    }
}
