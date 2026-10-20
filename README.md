# Forkcast

[![CI](https://github.com/asthaberi/forkcast/actions/workflows/ci.yml/badge.svg)](https://github.com/asthaberi/forkcast/actions/workflows/ci.yml)

AI-powered food delivery backend built with Java 21 and Spring Boot 4: a ground-up rebuild of my earlier project, QEats.

## Tech stack

| Layer | Technology |
|---|---|
| Language / runtime | Java 21 (virtual threads) |
| Framework | Spring Boot 4.1 (Web MVC, Validation, Actuator) |
| Security | Spring Security 7, OAuth2 resource server (JWT, HS256), BCrypt |
| Database | MongoDB 8 replica set (geospatial queries, multi-document transactions) |
| Cache / messaging / vector store | Redis 8 (Spring Cache, pub/sub, Lua rate limiting) |
| AI | Spring AI 2.0 + Google Gemini (embeddings, structured JSON chat output), Redis 8 vector search |
| Testing | JUnit 5, Mockito, MockMvc, Testcontainers |
| API docs | springdoc-openapi (OpenAPI 3.1, Swagger UI) |
| Build & delivery | Maven wrapper, multi-stage Docker image, GitHub Actions → GitHub Container Registry |

## Roadmap

- [x] **Step 1:** Project skeleton, configuration, local infrastructure
- [x] **Step 2:** Restaurants with MongoDB geo search and peak-hour serving radius
- [x] **Step 3:** Menus and Redis caching (benchmarked: [docs/caching.md](docs/caching.md))
- [x] **Step 4:** Cart with single-restaurant rule, live pricing and optimistic locking ([docs/cart.md](docs/cart.md))
- [x] **Step 5:** Orders: transactional, idempotent checkout ([docs/orders.md](docs/orders.md))
- [x] **Step 6:** JWT authentication, roles and restaurant ownership ([docs/security.md](docs/security.md))
- [x] **Step 7:** Order state machine and live tracking over SSE, scaled out with Redis pub/sub ([docs/order-tracking.md](docs/order-tracking.md))
- [x] **Step 8:** Distributed rate limiting: Redis token buckets in an atomic Lua script ([docs/rate-limiting.md](docs/rate-limiting.md))
- [x] **Step 9:** OpenAPI/Swagger docs, Docker image, GitHub Actions CI ([docs/delivery.md](docs/delivery.md))
- [x] **Step 10:** Semantic dish search with Gemini embeddings + Redis vector search, evaluated against BM25 ([docs/semantic-search.md](docs/semantic-search.md))
- [x] **Step 11:** Verified reviews with atomic ratings, and Gemini review summaries hardened against prompt injection ([docs/reviews.md](docs/reviews.md))
- [ ] **Step 12:** Personalized recommendations

## Running locally

Prerequisites: JDK 21 and Docker Desktop. Integration tests start their own MongoDB with Testcontainers
(they're skipped automatically if Docker isn't running): `./mvnw test`.

```bash
docker compose up -d      # start MongoDB (single-node replica set) + Redis
./mvnw spring-boot:run    # start the API on http://localhost:8080
```

Or run the published image (built and pushed by CI on every commit to `main`):

```bash
docker pull ghcr.io/asthaberi/forkcast:latest
```

Or run everything, including the app, in Docker:

```bash
docker compose --profile app up -d --build
```

**API docs:** <http://localhost:8080/swagger-ui.html>. Log in with a demo account, click **Authorize** and
paste the token.

Health check: <http://localhost:8080/actuator/health> (plus `/liveness` and `/readiness`)
Metrics such as cache hit/miss counts: `/actuator/metrics/cache.gets` *(admin token)*

On first start, 12 sample restaurants around Gwalior, 62 menu items, 63 reviews and three demo accounts (admin, the owner of
Dosa Junction, a customer; see [docs/security.md](docs/security.md)) are seeded. `SEED_ENABLED=false` turns it off.

## API

### Authentication

| Method | Path | Description |
|---|---|---|
| `POST` | `/api/v1/auth/register` | `{name, email, password, accountType?}`; `accountType` is `CUSTOMER` (default) or `RESTAURANT_OWNER` |
| `POST` | `/api/v1/auth/login` | `{email, password}` → `{accessToken, tokenType: "Bearer", expiresIn}` |
| `GET` | `/api/v1/users/me` | The signed-in user |

Send the token as `Authorization: Bearer <token>`. Browsing restaurants and menus is public; the cart and
orders need a customer, and menu edits need the restaurant's owner or an admin
([full rules](docs/security.md)).

```bash
TOKEN=$(curl -s -X POST localhost:8080/api/v1/auth/login -H "Content-Type: application/json"   -d '{"email":"customer@forkcast.example","password":"customer-dev-password"}' | jq -r .accessToken)
```

### Restaurants

| Method | Path | Description |
|---|---|---|
| `GET` | `/api/v1/restaurants?latitude=&longitude=` | Restaurants near a point, nearest first |
| `GET` | `/api/v1/restaurants/{id}` | One restaurant |
| `POST` | `/api/v1/restaurants` | Create a restaurant *(owner/admin; the creator becomes its owner)* |

Search parameters:

| Param | Required | Notes |
|---|---|---|
| `latitude`, `longitude` | yes | User location |
| `radiusKm` | no | Max 20. Defaults to the **serving radius**: 3 km during peak hours (8–10, 13–14, 19–21 IST), 5 km otherwise |
| `cuisine` | no | Case-insensitive exact match, e.g. `biryani` |
| `openNow` | no | `true` returns only restaurants open right now |

```bash
curl "http://localhost:8080/api/v1/restaurants?latitude=26.2124&longitude=78.1772&cuisine=mughlai"
```

### Menus

| Method | Path | Description |
|---|---|---|
| `GET` | `/api/v1/restaurants/{id}/menu` | Menu grouped by category (cached) |
| `POST` | `/api/v1/restaurants/{id}/menu/items` | Add an item *(that restaurant's owner, or admin)* |
| `PUT` | `/api/v1/restaurants/{id}/menu/items/{itemId}` | Replace an item |
| `DELETE` | `/api/v1/restaurants/{id}/menu/items/{itemId}` | Remove an item |

Items have a name, description, category, price (stored as exact `Decimal128`), vegetarian flag,
spice level (`NONE`/`MILD`/`MEDIUM`/`HOT`), tags and availability.

### Cart

Cart endpoints need a customer token. Every response is the full, freshly priced cart: items,
subtotal, delivery fee (₹30, free from ₹499), 5% GST and total.

| Method | Path | Description |
|---|---|---|
| `GET` | `/api/v1/cart` | Current cart |
| `POST` | `/api/v1/cart/items` | Add `{menuItemId, quantity}`; `?replace=true` to swap restaurants |
| `PUT` | `/api/v1/cart/items/{menuItemId}` | Set `{quantity}`; 0 removes |
| `DELETE` | `/api/v1/cart/items/{menuItemId}` | Remove an item |
| `DELETE` | `/api/v1/cart` | Empty the cart |

```bash
curl -X POST localhost:8080/api/v1/cart/items -H "Authorization: Bearer $TOKEN"      -H "Content-Type: application/json" -d '{"menuItemId":"<id>","quantity":2}'
```

### Orders

Need a customer token. Placing an order also needs an `Idempotency-Key` header
(e.g. a UUID per checkout); repeating the request with the same key returns the same order.

| Method | Path | Description |
|---|---|---|
| `POST` | `/api/v1/orders` | Place an order from the cart, body `{deliveryAddress: {line, latitude, longitude}}` → `201`, or `200` + `Idempotent-Replayed: true` |
| `GET` | `/api/v1/orders?page=0&size=10` | Order history, newest first |
| `GET` | `/api/v1/orders/{id}` | Order details with status history |
| `POST` | `/api/v1/orders/{id}/cancel` | Cancel while still `PLACED` |
| `GET` | `/api/v1/orders/{id}/events` | **Live tracking** (Server-Sent Events): current status, then every change |

### Restaurant orders

For the restaurant's owner (or an admin):

| Method | Path | Description |
|---|---|---|
| `GET` | `/api/v1/restaurants/{id}/orders?status=PLACED` | Order queue, newest first, optional status filter |
| `POST` | `/api/v1/restaurants/{id}/orders/{orderId}/status` | `{"status": ...}`: ACCEPTED/REJECTED → PREPARING → OUT_FOR_DELIVERY → DELIVERED |
| `GET` | `/api/v1/restaurants/{id}/orders/events` | Live feed of new orders and status changes (SSE) |

```bash
curl -N -H "Authorization: Bearer $TOKEN" localhost:8080/api/v1/orders/<orderId>/events
```

### AI search

| Method | Path | Description |
|---|---|---|
| `GET` | `/api/v1/search/dishes?q=` | Find dishes by meaning; optional `latitude`/`longitude`/`radiusKm`, `vegetarian`, `maxPrice`, `limit` |
| `POST` | `/api/v1/admin/search/reindex` | Rebuild the index *(admin)* |

```bash
curl "localhost:8080/api/v1/search/dishes?q=something+to+cool+me+down+on+a+hot+day&latitude=26.2124&longitude=78.1772"
```

Needs `GEMINI_API_KEY` (free from [Google AI Studio](https://aistudio.google.com/apikey)) in your environment or
a `.env` file; without it the endpoint answers `503` and everything else works.

### Reviews

| Method | Path | Description |
|---|---|---|
| `POST` | `/api/v1/orders/{id}/review` | Review a delivered order, once: `{rating: 1-5, text}` *(the customer who ordered)* |
| `GET` | `/api/v1/restaurants/{id}/reviews?page=0&size=10` | Reviews, newest first |
| `GET` | `/api/v1/restaurants/{id}/reviews/summary` | Average, star distribution and an AI summary: pros, cons, sentiment, dishes mentioned |

```bash
curl localhost:8080/api/v1/restaurants/<restaurantId>/reviews/summary
```

The summary also uses `GEMINI_API_KEY`; without it, a restaurant with no stored summary answers `503`.

### Errors

Errors use the RFC 9457 Problem Details format:

```json
{ "status": 400, "title": "Invalid request", "detail": "Request validation failed",
  "errors": { "latitude": "must be less than or equal to 90.0" } }
```

Rate-limited requests get `429` with `Retry-After`; every `/api` response carries `RateLimit-Limit` / `RateLimit-Remaining`.
Business-rule violations add a machine-readable `code`, e.g. `CART_RESTAURANT_MISMATCH`, `ITEM_UNAVAILABLE`,
`QUANTITY_LIMIT_EXCEEDED`, `CONCURRENT_MODIFICATION`, `CART_EMPTY`, `RESTAURANT_CLOSED`, `OUT_OF_DELIVERY_RANGE`, `ORDER_NOT_CANCELLABLE`, `INVALID_STATUS_TRANSITION`,
`REVIEW_NOT_ALLOWED`, `ALREADY_REVIEWED`, `AI_SUMMARY_UNAVAILABLE`.

## Design notes

- **Geo search:** restaurant locations are GeoJSON points with a `2dsphere` index, queried with `$nearSphere` + `$maxDistance`. MongoDB uses the index and returns results already sorted by distance. (The original QEats bucketed locations by geohash in application code.)
- **Time-dependent rules** (open now, peak hours) read time from an injected `Clock` in an explicit business time zone, so they're testable and don't depend on the server's zone.
- **Layers:** controller → service → repository, with request/response DTOs kept separate from the MongoDB document.
- **Caching:** menus and nearby search are cached in Redis. Search is keyed by ~150 m geohash cell so
  nearby users share entries, while distance and open-now are still computed per request. Menu writes
  evict the cache, and a Redis outage falls back to MongoDB instead of failing. Benchmark: **72.7% fewer
  MongoDB queries from a cold cache, 100% at steady state** ([details](docs/caching.md)).
- **Cart concurrency:** optimistic locking (`@Version`) with re-read-and-retry. Fired 20 simultaneous adds at one
  cart: without it, 86 of 100 "successful" adds were silently lost; with it, zero ([details](docs/cart.md)).
- **Checkout** creates the order and empties the cart in one MongoDB transaction, is idempotent via an
  `Idempotency-Key` header with a unique index, and retries only genuine conflicts (incl. MongoDB's
  `TransientTransactionError` label). Verified with Testcontainers: 10 simultaneous taps → exactly one order
  ([details](docs/orders.md)).
- **Order lifecycle:** an explicit state machine (one table of allowed moves and who may make them); live
  tracking over SSE, with events published to Redis pub/sub after commit so any instance can serve any stream.
  Streams close before graceful shutdown, so deploys don't stall ([details](docs/order-tracking.md)).
- **Semantic search:** dishes embedded with Gemini (`RETRIEVAL_DOCUMENT` vs `RETRIEVAL_QUERY`) into Redis
  vector search, with location/veg/price filters inside the KNN query and results read fresh from MongoDB.
  On 20 labelled natural-language queries: **Hit@1 0.95 vs 0.80 and Recall@5 0.98 vs 0.71 for BM25**; the
  minimum score is picked by a threshold sweep ([details](docs/semantic-search.md)).
- **Reviews and AI summaries:** verified-purchase reviews (unique index per order), ratings updated in one
  atomic MongoDB pipeline update (20 concurrent reviews → exact average). Gemini summaries are stored and
  refreshed stale-while-revalidate with single-flight (10 simultaneous requests → 1 LLM call). Prompt-injection
  defences are layered, model output is validated against the menu, and the numbers always come from the
  database. Flash-Lite was picked by measurement (2.1 s vs 12.9 s), and a live run caught thinking tokens
  truncating the JSON ([details](docs/reviews.md)).
- **Delivery:** layered, non-root Docker image (code changes ship as a <1 MB layer), Compose for the full
  stack, and CI that runs all tests, including Testcontainers, before building and publishing the image
  ([details](docs/delivery.md)).
- **Rate limiting:** token buckets in Redis, checked by one atomic Lua script so limits hold across instances
  (100 concurrent requests through two connections → exactly the 20 allowed). Per-IP and per-account login limits,
  per-user API and checkout limits, `429` + `Retry-After`; fails open with a circuit breaker if Redis is down
  ([details](docs/rate-limiting.md)).
- **Security:** stateless JWTs validated by Spring Security's resource server; roles in URL rules plus
  an object-level ownership check (`@PreAuthorize`) so owners edit only their own menus; login gives the
  same answer and timing for unknown emails and wrong passwords ([details](docs/security.md)).
- **Prices are never stored in the cart:** every response re-prices against the current (cached) menu with `BigDecimal`.
- **Opening hours** are stored as `"HH:mm"` strings, so they don't shift with the server's time zone.
