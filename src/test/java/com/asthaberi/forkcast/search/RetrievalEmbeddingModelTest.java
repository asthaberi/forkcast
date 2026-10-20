package com.asthaberi.forkcast.search;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.math.BigDecimal;
import java.util.List;

import com.asthaberi.forkcast.menu.MenuItem;
import com.asthaberi.forkcast.menu.SpiceLevel;
import com.asthaberi.forkcast.restaurant.Restaurant;
import org.junit.jupiter.api.Test;
import org.springframework.ai.embedding.EmbeddingModel;
import org.springframework.ai.embedding.EmbeddingRequest;

class RetrievalEmbeddingModelTest {

    private final EmbeddingModel documents = mock(EmbeddingModel.class);
    private final EmbeddingModel queries = mock(EmbeddingModel.class);
    private final RetrievalEmbeddingModel model = new RetrievalEmbeddingModel(documents, queries, 768);

    @Test
    void searchTextUsesTheQueryModelAndIndexingUsesTheDocumentModel() {
        when(queries.embed("spicy veg")).thenReturn(new float[] {1f});
        EmbeddingRequest batch = new EmbeddingRequest(List.of("Masala Dosa"), null);

        assertThat(model.embed("spicy veg")).containsExactly(1f);
        model.call(batch);

        verify(queries).embed("spicy veg");
        verify(documents).call(batch);
        verify(documents, never()).embed("spicy veg");
    }

    @Test
    void reportsTheConfiguredSizeNotTheProvidersDefault() {
        // Spring AI's Gemini model says 3072 even when configured for 768; the index must be sized for 768
        when(documents.dimensions()).thenReturn(3072);

        assertThat(model.dimensions()).isEqualTo(768);
    }

    @Test
    void embeddingTextDescribesTheDishInWordsButLeavesPriceToFilters() {
        MenuItem item = MenuItem.builder().id("i1").restaurantId("r1").name("Mysore Masala Dosa")
                .description("Dosa smeared with fiery red chilli-garlic chutney.").category("Dosas")
                .price(new BigDecimal("149")).vegetarian(true).spiceLevel(SpiceLevel.HOT)
                .tags(List.of("spicy", "breakfast")).available(true).build();
        Restaurant restaurant = Restaurant.builder().id("r1").name("Dosa Junction").cuisines(List.of("south indian"))
                .build();

        String text = DishDocuments.embeddingText(item, restaurant);

        assertThat(text).contains("Mysore Masala Dosa", "fiery red chilli", "Vegetarian", "hot spicy",
                "south indian", "breakfast", "Dosa Junction").doesNotContain("149");
        assertThat(DishDocuments.toDocument(item, restaurant).getMetadata())
                .containsEntry("vegetarian", "true").containsEntry("price", 149.0).containsEntry("available", "true");
    }
}
