# Forkcast

AI-powered food delivery backend built with Java 21 and Spring Boot 4: a ground-up rebuild of my earlier project, QEats.

## Tech stack

| Layer | Technology |
|---|---|
| Language / runtime | Java 21 (virtual threads) |
| Framework | Spring Boot 4.1 (Web MVC, Validation, Actuator) |
| Database | MongoDB 8 (geospatial queries) |
| Cache / vector store | Redis 8 |
| AI | Spring AI + Google Gemini *(planned)* |
| Build | Maven (wrapper included) |

## Roadmap

- [x] **Step 1:** Project skeleton, configuration, local infrastructure
- [ ] **Phase 1, core:** restaurants (geo search), menus, cart, orders, Redis caching
- [ ] **Phase 2, production-grade:** JWT auth + roles, order state machine, live order tracking (SSE), rate limiting, OpenAPI docs, Testcontainers, CI
- [ ] **Phase 3, AI:** semantic dish search, review summarization, personalized recommendations

## Running locally

Prerequisites: JDK 21 and Docker Desktop.

```bash
docker compose up -d      # start MongoDB + Redis
./mvnw spring-boot:run    # start the API on http://localhost:8080
```

Health check: <http://localhost:8080/actuator/health>

On first start, 12 sample restaurants around Gwalior are seeded (`SEED_ENABLED=false` turns it off).

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

Errors use the RFC 9457 Problem Details format:

```json
{ "status": 400, "title": "Invalid request", "detail": "Request validation failed",
  "errors": { "latitude": "must be less than or equal to 90.0" } }
```

## Design notes

- **Geo search:** restaurant locations are GeoJSON points with a `2dsphere` index, queried with `$nearSphere` + `$maxDistance`. MongoDB uses the index and returns results already sorted by distance. (The original QEats bucketed locations by geohash in application code.)
- **Time-dependent rules** (open now, peak hours) read time from an injected `Clock` in an explicit business time zone, so they're testable and don't depend on the server's zone.
- **Layers:** controller → service → repository, with request/response DTOs kept separate from the MongoDB document.
