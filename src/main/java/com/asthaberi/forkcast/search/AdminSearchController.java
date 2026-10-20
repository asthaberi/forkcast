package com.asthaberi.forkcast.search;

import java.util.Map;

import com.asthaberi.forkcast.common.exception.BusinessException;
import com.asthaberi.forkcast.common.exception.ErrorCode;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@Tag(name = "Admin", description = "Operational endpoints for administrators")
@RequestMapping("/api/v1/admin/search")
@RequiredArgsConstructor
public class AdminSearchController {

    private final DishIndexer indexer;
    private final DishVectorIndex index;

    /** Re-embeds every dish, e.g. after restaurant names change or a failed incremental update. */
    @PostMapping("/reindex")
    @Operation(summary = "Rebuild the semantic search index from all menu items")
    public Map<String, Object> reindex() {
        if (!index.isAvailable()) {
            throw new BusinessException(ErrorCode.AI_SEARCH_UNAVAILABLE, "No Gemini API key configured");
        }
        long start = System.nanoTime();
        int indexed = indexer.reindexAll();
        return Map.of("indexed", indexed, "tookMs", (System.nanoTime() - start) / 1_000_000);
    }
}
