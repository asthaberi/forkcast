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
