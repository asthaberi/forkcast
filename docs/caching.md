# Caching design and benchmark

## What is cached

| Cache | Key | Value | Invalidation |
|---|---|---|---|
| `menus` | restaurant id | full grouped menu (`MenuResponse`) | evicted on every item create/update/delete, plus 30 min TTL as a safety net |
| `nearbyRestaurants` | `geohashCell:radiusKm:cuisine` | restaurants near the cell center (`List<RestaurantSnapshot>`) | 5 min TTL only |

Both use the **cache-aside** pattern through Spring's `@Cacheable` / `@CacheEvict`, backed by Redis.

## Key decisions

**Geohash cells as the search cache key.** Exact coordinates almost never repeat, so a cache keyed on
`lat,lng` would never hit. The search rounds the user's location to a precision-7 geohash cell
(~153 m × 153 m) and queries MongoDB from the **cell center**, so everyone in the cell shares one
entry. The original QEats used the same idea.

**Per-request fields are never cached.** Distance (depends on the user's exact position) and `openNow`
(depends on the current time) are computed on every request from the cached data. Results are
re-sorted by the user's own distance, because the cached order was computed from the cell center.

**Snapshots, not entities, in the cache.** The cached value is a plain record (`RestaurantSnapshot`),
not the MongoDB `Restaurant` document. The cache format then doesn't depend on persistence types like
`GeoJsonPoint`, which Jackson 3 can't deserialize anyway.

**Typed JSON serializers.** Each cache has its own `JacksonJsonRedisSerializer` for its value type.
Entries are readable JSON in `redis-cli`, contain no Java class names, and don't need `Serializable`.

**The cache must never take the API down.** Redis timeouts are 500 ms, and a `LoggingCacheErrorHandler`
turns cache errors into warnings, so requests fall through to MongoDB. Verified by stopping the Redis
container during a run: both endpoints kept answering `200` (~0.5 s each, the timeout).

**Separate bean for the cached lookup.** `@Cacheable` works through a Spring proxy, so a call from
`RestaurantService` to its own method would skip the cache. The lookup therefore lives in
`NearbyRestaurantFinder`.

## Trade-offs and known limitations

- **Edge accuracy.** Querying from the cell center means a user near a cell edge can see restaurants
  up to ~110 m beyond the nominal radius (or miss one just inside it).
- **Staleness.** A new restaurant appears in nearby search within 5 minutes (TTL), not instantly.
  Menus are always fresh because writes evict.
- **Cache stampede.** When several requests miss the same key at once, each queries MongoDB. That's
  why the cold-cache runs below show 870–885 commands instead of a fixed number. `@Cacheable(sync = true)`
  would fix it, but `RedisCache` implements it with one lock per cache, which would serialize *all*
  misses. Per-key request coalescing is a possible later improvement.
- **Redis outage cost.** During an outage every request pays the 500 ms timeout. A circuit breaker
  (e.g. Resilience4j) would skip Redis entirely while it's down.

## Benchmark

### Method

`benchmark/cache_benchmark.py` replays a fixed, seeded workload (seed 42) of **1,500 user sessions =
2,350 requests**: each session runs one nearby search (70% plain, 20% cuisine filter, 10% open-now) and
60% of sessions also open a menu. User locations are clustered: 70% within ~100 m of one of 40
neighbourhood hotspots, 30% anywhere within 3 km of the city centre. 20 concurrent clients.

Rate limiting was added later (step 8); rerun these with `RATE_LIMIT_ENABLED=false`, or the API-wide limit
will answer most of the single-IP traffic with `429`.

- **No cache:** app started with `CACHE_TYPE=none`.
- **Redis, cold:** cache flushed before the run, so it starts empty. This is the conservative number.
- **Redis, warm:** the same workload replayed with the cache already full. This is the steady state.

Each configuration ran after a 300-session JVM warm-up with a different seed. MongoDB command counts
come from Micrometer's `mongodb.driver.commands` metric and cache hits from `cache.gets`.

Environment: laptop, Windows 11, MongoDB 8 and Redis 8 in Docker Desktop, 12 restaurants and 62 menu
items. Values below are medians across runs (3 no-cache, 3 cold, 4 warm). Raw JSON is in
`benchmark/results/`.

### Results

| | No cache | Redis (cold start) | Redis (warm) |
|---|---|---|---|
| MongoDB commands per 2,350 requests | 3,200 | 875 (**−72.7%**) | 0 (**−100%**) |
| Cache hit ratio | – | 63.4% | 100% |
| Search latency p50 / p95 / p99 (ms) | 11.4 / 16.1 / 26.4 | 13.1 / 24.6 / 63.7 | 10.3 / 15.2 / 24.1 |
| Menu latency p50 / p95 / p99 (ms) | 13.7 / 18.4 / 26.9 | 10.1 / 16.2 / 165.6 | 9.6 / 13.9 / 20.5 |
| Throughput (req/s) | 1,530 | 1,390 | 1,845 (**+21%**) |
| Errors | 0 | 0 | 0 |

Warm-run details: search p50 10.27, 10.36, 10.44, 9.91 ms; throughput 1,464, 1,848, 1,842, 1,944 req/s.
The first warm run (saved as `bench-redis-warm.json`) had a one-off p99 spike to 358 ms that didn't
reproduce in three repeats (20.5–24.7 ms).

### Reading the numbers

- **The cache's main win is database offload.** Even starting empty, the cache cut MongoDB commands
  by ~73% for this workload; at steady state the read path doesn't touch MongoDB at all.
- **Latency gains are modest here** because MongoDB is local and the dataset is tiny, so a MongoDB
  query is already about as cheap as a Redis round trip. Menus gain more (−30% p50) because a menu
  miss costs two MongoDB queries (restaurant exists + items). The latency benefit would grow with
  real data sizes and a remote database.
- **Cold-start tails are worse:** a miss pays Redis GET + MongoDB + Redis SET, and concurrent misses
  stampede. This settles as the cache fills.
- The hit ratio depends on the workload. More spread-out users means more distinct cells and fewer hits.
