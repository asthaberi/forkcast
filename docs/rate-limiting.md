# Rate limiting

## What's limited

| Policy | Key | Limit | Why |
|---|---|---|---|
| `api` | user id, or client IP when signed out | 200 burst, refills 200/min | fair use; stops one client hogging the API |
| `login` | client IP | 10 burst, refills 10/min | password guessing from one machine |
| `login-account` | email | 10 burst, refills 10 per 15 min | password guessing against one account from many IPs |
| `register` | client IP | 5 burst, refills 5/hour | mass account creation |
| `place-order` | user id | 10 burst, refills 10/min | runaway or scripted checkouts |

All of these are configurable under `forkcast.rate-limit.policies.*`. `RATE_LIMIT_ENABLED=false` turns
everything off, which load tests from a single machine need: `benchmark/cache_benchmark.py` sends
2,000+ requests from one IP.

## Algorithm: token bucket in a Redis Lua script

Each bucket holds up to `capacity` tokens and refills continuously at `refill-tokens` per `refill-period`.
A request takes a token or is rejected with the exact wait until the next one. A token bucket allows short
bursts (opening the app fires several requests at once) while capping the average rate. A fixed window
would let a client send 2× the limit across a window boundary; a sliding log costs memory per request.

The whole check runs as one Lua script (`scripts/token_bucket.lua`). **Redis executes a script
atomically**, so "read the bucket → refill → take a token → write it back" can't interleave with another
request, even one handled by a different app instance. Doing the same steps as separate commands from Java
would let two instances read the same token and both spend it.

Details:

- It uses Redis's own clock (`TIME`), so app servers with skewed clocks still agree on refills.
- Buckets get a TTL equal to the time to refill completely. An idle bucket is just a full bucket, so
  it expires and memory stays bounded.
- Keys are SHA-256 hashed (`forkcast:rate-limit:login-account:3f2a...`), so emails aren't stored in
  Redis in plain text.

## Where the checks run

- **`ApiRateLimitFilter`** sits in the Spring Security filter chain right after the bearer token is
  verified. It can key on the real user, and it rejects before any controller or database work. It's
  created inside `SecurityConfig` rather than declared as a `@Bean`, because Spring Boot also registers
  every `Filter` bean as a servlet filter, which would make it run twice per request.
- **`@RateLimit(policy = "login", by = IP)`** on controller methods, enforced by `RateLimitInterceptor`
  (a `MappedInterceptor` bean, so web-slice tests don't load it).
- **Per-account login** is checked inside `AuthService.login`, because the key (the email) is in the
  request body. It applies to unknown emails too, so the limit doesn't reveal which accounts exist.

### Responses

```
HTTP/1.1 429 Too Many Requests
Retry-After: 6
RateLimit-Limit: 200
RateLimit-Remaining: 0
Content-Type: application/problem+json

{"status":429,"title":"Too many requests","detail":"Too many requests. Try again in 6 s.",
 "code":"RATE_LIMITED","retryAfterSeconds":6}
```

Every `/api` response carries `RateLimit-Limit` and `RateLimit-Remaining`, so well-behaved clients can
slow down before they're blocked. `Retry-After` is rounded up so clients never retry early.

### Client IP

The key is the TCP connection's address, **not** `X-Forwarded-For`, which any client can set to a new
value on each request to dodge per-IP limits. Behind a load balancer, set
`server.forward-headers-strategy=native`: Tomcat then takes the client IP from that header only when the
request comes from a trusted proxy address.

## When Redis is unavailable: fail open, with a circuit breaker

If Redis can't be reached, the limiter **allows** the request and counts it as
`forkcast.rate_limit.decisions{outcome="error"}`. Limits protect against abuse; they shouldn't take the
whole API down. After a failure it **skips Redis for 5 seconds** (`outcome="skipped"`). Without that,
every request during an outage would first wait out the 500 ms Redis timeout.

Measured live with Redis stopped: a search took ~1,050 ms on the first request (rate limit and cache both
timing out), then ~520 ms on later ones, because the rate-limit check was skipped. One warning was logged
instead of one per request. The remaining 500 ms is the response cache's own Redis timeout. The same
circuit-breaker idea would remove it, and that's a natural follow-up.

**A bug the breaker test caught:** the first version initialised its "skip until" time to `Long.MIN_VALUE`.
`System.nanoTime()` values can only be compared by subtraction, and `now - Long.MIN_VALUE` overflows to a
negative number, so the limiter believed it was always in the skip window and **silently disabled every
limit**. It now starts the window at "now", already expired, which can't overflow.

## Verified

- `RedisRateLimiterIntegrationTest` (real Redis): burst then rejection with the right wait (12 s for
  5/min), refill over time, hashed keys with the right TTL, fail-open plus breaker timing, and
  **100 simultaneous requests through two separate connections ("two instances") let exactly 20 through.**
- `RateLimitHttpIntegrationTest`: 11th login from one IP gets `429` + `Retry-After` while another IP
  still works; one account guessed from 10 different IPs is blocked on the 11th; `RateLimit-*` headers;
  `/actuator/health` isn't limited.
- Live, two instances sharing Redis, alternating requests: 10 wrong passwords answered `401`, then `429`.
  The limit is shared across instances.

### Stacked limits

Live testing showed how the two login limits interact. After 10 guesses, the per-IP limit said
"retry in 5 s". After waiting, the request passed the IP limit but was stopped by the **per-account**
limit ("retry in 55 s"), because the guesses had used up that bucket too. Each `429` is accurate for the
limit that triggered it.

This is the known trade-off of per-account limits: an attacker can lock the real user out for a while.
The limit is set so that's at most ~90 s per extra guess, rather than a hard lockout. Next steps could be
counting only failed attempts per account, or adding a CAPTCHA after repeated failures.

### Cost

One extra Redis round trip per API request. Measured on this laptop (2 app instances + Docker Desktop on one
machine, 20 concurrent clients, warm cache, medians of 4 rounds each): search p50 ~19 ms with limiting vs
~15 ms without, and throughput ~910 vs ~1,230 req/s. Much of that is Docker Desktop's network path to Redis;
in a cloud deployment with Redis in the same zone, a round trip is typically well under 1 ms. If it mattered,
a small in-process token cache in front of Redis could absorb most checks.
