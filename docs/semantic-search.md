# Semantic dish search

`GET /api/v1/search/dishes?q=something to cool me down on a hot day` finds dishes by **meaning**, not keywords:
lassi, buttermilk and a milkshake, although none of those words are in the query. It also works across
languages: the Hinglish "kuch meetha khana hai" ("I want something sweet") returns Indian desserts from
English menus.

| Param | Notes |
|---|---|
| `q` | required, up to 200 characters |
| `latitude`, `longitude`, `radiusKm` | optional; only restaurants delivering there (same serving radius as restaurant search), with distances |
| `vegetarian`, `maxPrice` | optional filters |
| `limit` | 1–30, default 10 |

The endpoint is public, with its own rate limit (`ai-search`: 20 per minute per user or IP), because every
search costs an embedding API call. Without a Gemini key it answers `503 AI_SEARCH_UNAVAILABLE`, and the
rest of the app works normally.

## How it works

```
 indexing (background)                               searching
 ─────────────────────                               ─────────
 menu item ──► text: "Masala Dosa. Crispy...         query ──► Gemini (RETRIEVAL_QUERY) ──► 768-d vector
               South Indian. Vegetarian. Mild...                                        │
               Tags: breakfast. From Dosa Junction."                                    ▼
            ──► Gemini (RETRIEVAL_DOCUMENT)       ──►  Redis vector index (HNSW, cosine)
            ──► 768-d vector + filter fields            KNN top-k, pre-filtered by
                (restaurantId, vegetarian,              available, nearby restaurants, vegetarian, price
                 available, price, spiceLevel)                                          │
                                                                                         ▼
                                                     sort by score ──► details from MongoDB ──► response
```

- **Embeddings:** Google's `gemini-embedding-001` through **Spring AI 2.0**. The model natively produces
  3072 dimensions; it supports truncation (Matryoshka), so it runs at **768** to keep the index small.
- **Asymmetric task types:** dishes are embedded as `RETRIEVAL_DOCUMENT` and queries as `RETRIEVAL_QUERY`
  (`RetrievalEmbeddingModel`), which is Gemini's recommended setup for search.
- **What gets embedded:** name, description, category, restaurant cuisine, veg/non-veg, spice level, tags and
  restaurant name, in plain words. Price is deliberately left out: it's a filter, not a matter of similarity.
- **Vector store:** Redis 8, which has vector search built in (Query Engine + JSON). That makes one Redis serve
  four jobs in this project: cache, pub/sub, rate limiter and vector database.
- **Filters inside the vector query:** `available`, nearby restaurant ids, `vegetarian` and `price` are indexed
  tag/numeric fields, so the KNN search only considers matching dishes. Filtering after a plain top-k could
  leave you with 2 usable results out of 10.
- **Location** reuses the cached restaurant geo search, so "near me" adds no database work.
- **Results are read from MongoDB:** the index decides which dishes match; names, current prices and
  availability come from MongoDB, the source of truth. A price change shows immediately, and a dish deleted a
  moment ago is skipped even if the asynchronous index update hasn't landed yet.

### Keeping the index current

| When | What happens |
|---|---|
| Startup, index empty (first run, or a new index version) | all dishes embedded in the background, batches of 100 |
| A menu item is created, updated or deleted | `MenuItemChangedEvent` → that one item is re-embedded or removed, asynchronously |
| `POST /api/v1/admin/search/reindex` (admin) | everything re-embedded, e.g. after restaurant renames |

Saving a menu item never waits on the Gemini API; the index can lag a change by about a second. The index name
is versioned (`forkcast-dishes-v1`): changing the model, dimensions or embedded text means bumping the version,
which builds a fresh index in the background.

## Evaluation

`benchmark/search_eval.py`: 20 natural-language queries with hand-labelled relevant dishes, mostly phrased
the way people talk rather than with menu words. It compares semantic search against **BM25**, a standard
keyword ranking, over exactly the same dish text. Results: `benchmark/results/search-eval.json`.

| | Hit@1 | Hit@3 | Recall@5 | MRR@10 |
|---|---|---|---|---|
| **Semantic (Gemini + Redis)** | **0.95** | **1.00** | **0.98** | **0.975** |
| Keyword (BM25) | 0.80 | 0.80 | 0.71 | 0.81 |

The difference is the paraphrased queries. "Something to cool me down on a hot day", "something with
caffeine" and "big vegetarian meal with lots of small dishes" score 0 with keywords (no shared words) and a
correct first result semantically. Semantic search's one miss: "rice slow cooked in a sealed pot" put Dal
Makhani (slow-cooked overnight) above the dum biryanis.

*Caveats:* 62 dishes and 20 queries, labelled by the same person who wrote the menu descriptions. It's a
sanity check and a regression baseline, not a research result.

### Choosing the minimum match score

Without a threshold, every query returns 10 dishes, including "quarterly tax filing". `--sweep` scores the 20
queries plus 8 off-topic ones (`benchmark/results/search-threshold-sweep.json`):

| Min score | Relevant matches kept | Off-topic queries answered with nothing |
|---|---|---|
| 0.73 | 100% | 3 of 8 |
| **0.75 (chosen)** | **100%** | **7 of 8** |
| 0.76 | 98.4% | 8 of 8 |
| 0.78 | 77.4% | 8 of 8 |

Scores are `(1 + cosine) / 2`. Gemini's sit in a narrow band: the lowest relevant match scored 0.757 and the
highest off-topic query 0.753. 0.75 favours recall: for a food app, hiding a relevant dish is worse than
occasionally showing food for a silly query. The margin is small, so the sweep should be re-run after changing
the model or the embedded text.

## Bugs found along the way

1. **App wouldn't start without an API key.** Spring AI's Gemini starter falls back to Vertex AI mode and
   demands a Google Cloud project id when no key is set. CI has no key. The plain library is used instead,
   and `AiConfig` creates the model only when a key exists (`@ConditionalOnExpression`).
2. **Results not ranked.** The Redis vector store returns the right top-k dishes, but not in score order.
   Caught by an integration test that compares the index's answer with brute-force cosine similarity over all
   dishes. The service now sorts by score.
3. **Every dish silently missing from the index.** On the first live run, the vector store sized the Redis
   index from `embeddingModel.dimensions()`, and Spring AI's Gemini model reports its default **3072** there even
   when configured for **768**. Redis accepted all 62 documents and indexed none
   (`hash_indexing_failures = 62`), while the log said "Indexed 62 dishes". Fixed by reporting the configured
   size. The indexer now also reads Redis's failure count after indexing and logs an error if it isn't zero,
   so this kind of failure can't be silent again.
4. **An empty `JWT_SECRET=` in `.env` overrode the development default** (Spring's `${VAR:default}` only applies
   when the property is missing, not empty), and the app correctly refused to start with a short secret. The
   template now leaves optional settings commented out.

## Testing without an API key

`DishSearchIntegrationTest` runs the real pipeline: real MongoDB, real Redis vector search, the real indexer
and filters. Only the embedding model is replaced, by `KeywordEmbeddingModel`, a deterministic stand-in that
hashes words into a vector. It checks that the index returns exactly the brute-force nearest neighbours,
best first; filters and location; and that menu edits, sold-out dishes and deletions reach the index without a
reindex. Semantic quality itself is what the evaluation script measures with the real model.

## Cost and latency

A search takes roughly 0.6–2 s, almost all of it the Gemini call that embeds the query; the Redis KNN query
itself is milliseconds. Natural next steps: cache query embeddings (popular searches repeat), and stream or
pre-compute for common queries. Indexing all 62 dishes is a single batch request.
