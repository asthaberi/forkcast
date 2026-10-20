package com.asthaberi.forkcast.review.summary;

import java.util.List;
import java.util.Locale;

import com.asthaberi.forkcast.review.Review;
import com.asthaberi.forkcast.search.GeminiProperties;
import lombok.RequiredArgsConstructor;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.model.Generation;
import org.springframework.ai.converter.BeanOutputConverter;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.stereotype.Component;

/** Review summaries written by Gemini through Spring AI's ChatClient, with JSON mapped onto a record. */
@Component
@RequiredArgsConstructor
class GeminiReviewSummarizer implements ReviewSummarizer {

    // Describes ReviewSummaryDraft as a JSON schema for the prompt, and parses the reply back into it
    private static final BeanOutputConverter<ReviewSummaryDraft> OUTPUT = new BeanOutputConverter<>(ReviewSummaryDraft.class);

    private final ObjectProvider<ChatModel> chatModel;
    private final GeminiProperties gemini;

    @Override
    public boolean isAvailable() {
        return chatModel.getIfAvailable() != null;
    }

    @Override
    public String modelName() {
        return gemini.chatModel();
    }

    @Override
    public ReviewSummaryDraft summarize(String restaurantName, List<String> menuDishes, List<Review> reviews) {
        ChatResponse response = ChatClient.create(chatModel.getObject())
                .prompt()
                .system(ReviewPrompt.SYSTEM)
                .user(ReviewPrompt.userMessage(restaurantName, menuDishes, reviews) + "\n" + OUTPUT.getFormat())
                .call()
                .chatResponse();
        Generation result = response == null ? null : response.getResult();
        if (result == null || result.getOutput().getText() == null) {
            throw new IllegalStateException("The model returned no text");
        }
        // A reply cut off at the token limit is broken JSON; say so instead of failing with a parse error
        String finishReason = result.getMetadata().getFinishReason();
        if (finishReason != null && finishReason.toUpperCase(Locale.ROOT).contains("MAX_TOKENS")) {
            throw new IllegalStateException("The summary was cut off at the output token limit");
        }
        return OUTPUT.convert(result.getOutput().getText());
    }
}
