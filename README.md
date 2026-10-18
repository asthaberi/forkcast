# Forkcast

AI-powered food delivery backend built with Java 21 and Spring Boot 4: a ground-up rebuild of my earlier project, QEats.

## Tech stack

| Layer | Technology |
|---|---|
| Language / runtime | Java 21 (virtual threads) |
| Framework | Spring Boot 4.1 (Web MVC, Validation, Actuator) |
| Database | MongoDB 8 (geospatial queries) |
| Cache / vector store | Redis 8 (Spring Cache) |
| AI | Spring AI + Google Gemini *(planned)* |
| Build | Maven (wrapper included) |

## Roadmap

- [x] **Step 1:** Project skeleton, configuration, local infrastructure
- [x] **Step 2:** Restaurants with MongoDB geo search and peak-hour serving radius
- [x] **Step 3:** Menus and Redis caching (benchmarked: [docs/caching.md](docs/caching.md))
- [x] **Step 4:** Cart with single-restaurant rule, live pricing and optimistic locking ([docs/cart.md](docs/cart.md))
- [ ] **Step 5:** Orders
- [ ] **Phase 2, production-grade:** JWT auth + roles, order state machine, live order tracking (SSE), rate limiting, OpenAPI docs, Testcontainers, CI
- [ ] **Phase 3, AI:** semantic dish search, review summarization, personalized recommendations

## Running locally

Prerequisites: JDK 21 and Docker Desktop.

```bash
docker compose up -d      # start MongoDB + Redis
./mvnw spring-boot:run    # start the API on http://localhost:8080
```

Health check: <http://localhost:8080/actuator/health>
Cache hit/miss counts: <http://localhost:8080/actuator/metrics/cache.gets>

On first start, 12 sample restaurants around Gwalior and 62 menu items are seeded (`SEED_ENABLED=false` turns it off).

## API

### Restaurants

| Method | Path | Description |
|---|---|---|
| `GET` | `/api/v1/restaurants?latitude=&longitude=` | Restaurants near a point, nearest first |
| `GET` | `/api/v1/restaurants/{id}` | One restaurant |
| `POST` | `/api/v1/restaurants` | Create a restaurant |

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
| `POST` | `/api/v1/restaurants/{id}/menu/items` | Add an item |
| `PUT` | `/api/v1/restaurants/{id}/menu/items/{itemId}` | Replace an item |
| `DELETE` | `/api/v1/restaurants/{id}/menu/items/{itemId}` | Remove an item |

Items have a name, description, category, price (stored as exact `Decimal128`), vegetarian flag,
spice level (`NONE`/`MILD`/`MEDIUM`/`HOT`), tags and availability.

### Cart

All cart endpoints need an `X-User-Id` header *(temporary, until JWT auth)*. Every response is the full,
freshly priced cart: items, subtotal, delivery fee (₹30, free from ₹499), 5% GST and total.

| Method | Path | Description |
|---|---|---|
| `GET` | `/api/v1/cart` | Current cart |
| `POST` | `/api/v1/cart/items` | Add `{menuItemId, quantity}`; `?replace=true` to swap restaurants |
| `PUT` | `/api/v1/cart/items/{menuItemId}` | Set `{quantity}`; 0 removes |
| `DELETE` | `/api/v1/cart/items/{menuItemId}` | Remove an item |
| `DELETE` | `/api/v1/cart` | Empty the cart |

```bash
curl -X POST localhost:8080/api/v1/cart/items -H "X-User-Id: astha"      -H "Content-Type: application/json" -d '{"menuItemId":"<id>","quantity":2}'
```

### Errors

Errors use the RFC 9457 Problem Details format:

```json
{ "status": 400, "title": "Invalid request", "detail": "Request validation failed",
  "errors": { "latitude": "must be less than or equal to 90.0" } }
```

Business-rule violations add a machine-readable `code`, e.g. `CART_RESTAURANT_MISMATCH`, `ITEM_UNAVAILABLE`,
`QUANTITY_LIMIT_EXCEEDED`, `CONCURRENT_MODIFICATION`.

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
- **Prices are never stored in the cart:** every response re-prices against the current (cached) menu with `BigDecimal`.
- **Opening hours** are stored as `"HH:mm"` strings, so they don't shift with the server's time zone.
