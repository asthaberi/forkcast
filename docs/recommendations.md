# Personal recommendations

`GET /api/v1/recommendations?latitude=&longitude=` *(customer token)* returns two lists:

- **orderAgain:** your favourites that are available now from restaurants that deliver to you;
- **forYou:** dishes you haven't tried, each with the reason it was picked.

| Param | Notes |
|---|---|
| `latitude`, `longitude` | required; only restaurants that deliver there are considered |
| `radiusKm` | optional, max 20; defaults to the serving radius (3 km at peak hours, 5 km otherwise) |
| `openNow` | optional; `true` keeps only restaurants open right now |
| `limit` | 1–20, default 10 |

Live output, for a customer who ordered Masala Dosa (reviewed 5 stars), Medu Vada and Gulab Jamun:

```
strategy: PERSONALIZED
orderAgain: Masala Dosa, Gulab Jamun, Medu Vada
forYou:
  Idli Sambar          Dosa Junction        Because you ordered Medu Vada     0.865
  Mysore Masala Dosa   Dosa Junction        Because you ordered Masala Dosa   0.862
  Samosa               Sweet Tooth Mithai   Because you ordered Gulab Jamun   0.804
  Appam (2 pcs)        Coastal Curry        Because you ordered Medu Vada     0.779
  Rasmalai             Sweet Tooth Mithai   Because you ordered Gulab Jamun   0.768
  ...
```

A brand-new customer gets `strategy: POPULAR`: "Ordered by 2 people near you this month", then dishes from the
best-rated restaurants nearby.

## How it works

```
orders (last 50) + own reviews ──► taste profile: liked dishes with weights, disliked dishes, veg-only?
                                         │
             strongest 5 liked dishes ──► their stored Gemini vectors (Redis JSON, no API call)
                                         │
                       weighted mean ──► taste vector ──► one KNN query in Redis
                                                          (available dishes, nearby restaurants, veg filter)
                                         │
 candidates: taste matches, then "popular near you", then top-rated restaurants
                                         │
 pick: skip dishes already ordered / disliked / unavailable (checked in MongoDB), max 2 per restaurant
                                         │
 explain: each pick ◄── the liked dish whose vector is closest ("Because you ordered Medu Vada")
```

### The taste profile (`TasteProfile`)

Each dish a customer ordered gets a weight that adds up the evidence:

| Signal | Effect |
|---|---|
| Quantity | counts up to 3 per order, so one family-size order isn't a habit |
| Recency | an order's weight halves every 30 days, so tastes can change |
| The customer's review of that order | 5 stars ×1.5, 4 ×1.2, 3 ×0.8, none ×1.0; **1–2 stars count against the dish** |

Dishes with a positive total are *liked*. Dishes the reviews pushed to zero or below are *disliked*: they are
never recommended or shown under "order again". Ordering a dish three times and rating it badly once still
leaves it liked. If at least 5 different dishes are known and all are vegetarian, only vegetarian dishes are
recommended. The bar is 5 because an earlier threshold of 3 was too eager in the live test: a few vegetarian
orders by chance would hide everything else.

Cancelled and rejected orders don't count. Seeded reviews (not linked to an order) are ignored.

### Cold start

A customer with no history gets **popular dishes nearby**, ranked by how many *different customers* ordered
them in the last 30 days (one MongoDB aggregation, served by the restaurant/status/date index). Counting
customers rather than portions means one big order can't make a dish "popular". If there aren't enough
orders in the area yet, the list is filled from the best-rated restaurants nearby.

### Rules every list follows

- **Freshness:** each recommended dish is checked against MongoDB, so a dish taken off the menu a moment ago
  isn't shown even before the asynchronous index update lands (there's a test for it).
- **Variety:** at most 2 dishes per restaurant. Restaurant names and cuisines are part of the embedded text,
  so without this cap one restaurant tends to fill the list.
- **"For you" is about new dishes:** anything already ordered goes in "order again" instead.

### No LLM call per request

The dish vectors already exist: they were computed by Gemini when the menu was indexed for semantic search.
A recommendation reads 5 of them from Redis, does the vector maths in Java and runs one KNN query. So it
costs no API quota and doesn't depend on Gemini being up. Measured on a laptop: the median is about 50–80 ms
end to end, against about 15 ms for a trivial authenticated endpoint on the same setup. If Redis or the index
is unavailable, the customer gets popularity-based recommendations instead of an error.

## Evaluation: which retrieval approach?

How do you know recommendations are good without real users? `RecommendationEvaluationTest` simulates
customers with **known tastes** and checks whether the recommender finds them.

- 8 hand-labelled taste groups (South Indian, Meaty Mughlai, Desserts, Healthy, Pizza & burgers, Chinese &
  Thai, Seafood, Street food) and 13 personas: 8 with one taste, 5 with **two unrelated tastes** (e.g.
  South Indian + Desserts).
- 20 customers per persona (260 in all). Each orders 3 random dishes from their group, or 2 from each of
  two groups. The rest of their groups' dishes are what a good recommender should suggest.
- The test runs on **real Gemini vectors**: the embeddings of all 62 seeded dishes were recorded from the live
  index into a fixture (`gemini-dishes-768.json.gz`, float16, ~100 KB). It runs in CI without an API key,
  and a change that hurts quality fails the build.
- Every strategy gets the same rules (nothing already ordered, 2 per restaurant), so only the ranking differs.

| Strategy | Recall@10, one taste | Recall@10, two tastes | Precision@5 | Both tastes covered |
|---|---|---|---|---|
| **Averaged taste vector (production)** | **0.83** | 0.57 | **0.58** | **100%** |
| One search per liked dish (best score) | 0.82 | 0.59 | 0.56 | 97% |
| Popular | 0.33 | 0.28 | 0.13 | 53% |
| Random | 0.18 | 0.18 | 0.10 | 30% |

Recall@10 is the share of a customer's not-yet-ordered taste-group dishes that appear in their top 10.
Personalised retrieval finds **2.5× more** of them than popularity. Two-taste recall is lower partly by
construction: a top 10 capped at 2 per restaurant can't hold all of two groups' dishes.

### My first design was wrong, and the measurement showed it

I first built **one search per liked dish**, with candidates scaled by preference strength. I expected that
averaging the liked dishes into a single taste vector would fail customers with two unrelated tastes: the
average of "dosa" and "dessert" would sit between them and match neither. The first run of this evaluation
(130 customers, two random seeds) said otherwise:

| Variant (first run) | Recall@10, one taste | Recall@10, two tastes | Both tastes covered |
|---|---|---|---|
| One search per liked dish, scaled by preference (my first build) | 0.79 / 0.81 | 0.53 / 0.55 | 94% / 96% |
| Averaged taste vector | 0.81 / 0.85 | 0.53 / 0.57 | 98% / 100% |
| One search per liked dish, unscaled | 0.80 / 0.83 | 0.56 / 0.59 | 96% / 100% |
| One search per liked dish, similarities summed | 0.81 / 0.84 | 0.43 / 0.46 | 86% / 86% |

Why averaging works: with cosine similarity, the dishes nearest to a weighted mean of unit vectors are the
ones with the highest weighted *sum* of similarities to everything the customer likes. That is close to
classic item-to-item scoring. In a 768-dimensional space, the mean of two clusters stays close to both
rather than landing in some third cluster. Averaging also needs **one vector query instead of five**. So
production switched to it, with each liked dish normalised to unit length first, and kept explainability by
finding each pick's closest liked dish afterwards.

### Limits of this evaluation

- The ground truth is my labelling of the 62 dishes into groups, and simulated customers order uniformly
  within their groups. Real behaviour is messier.
- The simulation can't measure the recency and rating weights (simulated orders carry no real preference
  signal), so those weights are chosen by reasoning, not tuned. A/B testing on real traffic would settle them.
- 62 dishes is a small catalogue; with thousands, the 2-per-restaurant cap and the KNN size would need
  revisiting.

### Should weak matches be cut off? Measured: no

In the live check, the 10th pick for one customer was a Crispy Veg Burger at score 0.64, explained as
"Because you ordered Masala Dosa". A minimum score looked like the obvious fix, so the evaluation now also
reports how often a taste match is relevant at each score:

| Score | Picks | Relevant |
|---|---|---|
| 0.65 to 0.70 | 315 | 2% |
| 0.70 to 0.75 | 1,095 | 7% |
| 0.75 to 0.80 | 776 | 54% |
| 0.80 and above | 414 | 93% |

On their own, matches below 0.75 look worse than popular dishes (13% of popular picks are relevant). But
cutting them off made the end-to-end result **worse**:

| Cutoff | Recall@10, one taste | Recall@10, two tastes | Precision@5 |
|---|---|---|---|
| none | **0.83** | 0.57 | 0.58 |
| 0.72 | 0.81 | 0.58 | 0.58 |
| 0.75 | 0.79 | 0.56 | 0.58 |
| 0.78 | 0.73 | 0.47 | 0.50 |

The popular dishes that would replace a customer's weak matches are, for that customer, even less relevant.
The most popular ones are often already ordered, already in the list or blocked by the 2-per-restaurant rule.
So there's no cutoff. The real problem was the explanation, which overclaimed: picks below 0.75 now say
**"Something different to try"**, and only stronger ones say "Because you ordered X".

## Tests

- `TasteProfileTest`: recency half-life, review factors, dislikes, quantity cap, the vegetarian-only rule.
- `RecommendationIntegrationTest` (MongoDB + Redis vector search + recorded Gemini vectors): cold start and
  popularity ranking, personal picks with explanations, the vegetarian-only rule, bad reviews, a dish taken
  off the menu, at most 2 per restaurant.
- `RecommendationEvaluationTest`: the evaluation above, with minimum quality bars.
- `RecommendationControllerTest`: customer-only access, validation.

## Next steps

- Cache the taste vector per customer (invalidated by a new order or review) to skip rebuilding the profile.
- Use time of day (breakfast vs dinner) and price range as signals.
