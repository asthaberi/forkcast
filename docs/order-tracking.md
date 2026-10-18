# Order lifecycle and live tracking

## State machine

```
           ┌──────────► REJECTED                                     (restaurant)
           │
  PLACED ──┼──────────► CANCELLED                                    (customer)
           │
           └─► ACCEPTED ─► PREPARING ─► OUT_FOR_DELIVERY ─► DELIVERED   (restaurant)
```

Every allowed move is listed once, with who may make it, in a table inside `OrderStatus`.
`Order.transitionTo(target, actor, at)` is the only way to change a status. Anything not in the table is
rejected with `409 INVALID_STATUS_TRANSITION`, which includes `from`, `to` and the `allowed` next steps.
A customer cancelling too late gets the more specific `409 ORDER_NOT_CANCELLABLE`. Steps can't be
skipped, a restaurant can't cancel on the customer's behalf, and `DELIVERED`, `CANCELLED` and `REJECTED`
are final. Each change is appended to `statusHistory` with its time and actor (customer or restaurant).

`OrderStatusTest` checks the table exhaustively: all 7 × 7 statuses × 2 actors = 98 combinations,
against the list of allowed moves.

### Concurrent changes

A customer cancelling while the restaurant accepts is a real race. `Order` has a `@Version`, so only one
save succeeds. The loser re-reads the order and re-applies its change, so the state machine judges it
against the winner's result. For example, the cancel becomes `ORDER_NOT_CANCELLABLE` because the order
is now `ACCEPTED`. `OrderTrackingIntegrationTest` fires both at once in 5 rounds: every round ends with
exactly one `200` and one `409`, and exactly two history entries.

## Endpoints

| Method | Path | Who |
|---|---|---|
| `POST` | `/api/v1/orders/{id}/cancel` | the customer, while `PLACED` |
| `GET` | `/api/v1/orders/{id}/events` | the customer: **live status stream** |
| `GET` | `/api/v1/restaurants/{id}/orders?status=PLACED&status=ACCEPTED&page=0&size=20` | that restaurant's owner, or admin |
| `POST` | `/api/v1/restaurants/{id}/orders/{orderId}/status` `{"status": "ACCEPTED"}` | that restaurant's owner, or admin |
| `GET` | `/api/v1/restaurants/{id}/orders/events` | that restaurant's owner, or admin: **live feed** of new orders and changes |

`GET /api/v1/restaurants/**` is public for browsing, so the security rule for `/restaurants/*/orders/**`
is declared **before** it. A slice test checks that an anonymous request gets `401`, not the public rule.
The restaurant's order queue is served by a `{restaurantId, status, placedAt desc}` index.

## Live tracking with Server-Sent Events

```
event: order-status
id: 6ad4...:3
data: {"orderId":"6ad4...","restaurantId":"6ad3...","status":"PREPARING","at":"2026-10-18T06:12:40Z","sequence":3}
```

- The **customer stream** first sends the order's current status, then every change. It closes itself
  once the order reaches a final status.
- The **restaurant feed** gets an event for every new order and every status change.
- Events carry no personal data, since they go to both sides.
- `sequence` is the position in the status history (also the SSE `id`), so a client can ignore an event
  older than one it already has.

**Why SSE and not WebSockets?** Updates only flow server → client. SSE is plain HTTP, so it works through
the existing security filter chain, proxies and load balancers, and it has reconnection built into the
protocol (`retry: 3000`). WebSockets would add a second protocol for a two-way channel that isn't needed.

**Authentication:** streams use the normal `Authorization: Bearer` header. The browser's built-in
`EventSource` can't set headers, so web clients use a fetch-based SSE client. Putting tokens in the URL
would leak them into server and proxy logs.

### Works across multiple app instances: Redis pub/sub

```
 client ──SSE── instance B                      instance A ◄── POST .../status (owner)
                    ▲                               │
                    │      ┌─────────────────┐      │ 1. save (version-checked)
                    └──────┤  Redis channel  │◄─────┘ 2. publish event
         3. forward to     │ forkcast:order- │
            local streams  │     events      │
                           └─────────────────┘
```

A live connection stays open on whichever instance the client connected to, while the status change
can be handled by any instance. So events aren't delivered in memory: `OrderEventBus` publishes to a Redis
channel, every instance subscribes (`RedisMessageListenerContainer`), and each forwards events to the
streams it holds (`OrderEventStreams`).

- **Published after commit:** status changes publish after the save succeeds, and a new order publishes
  after its transaction commits. A rolled-back change is never announced.
- **Redis down:** the publish is logged and dropped. The database is still correct, and a reconnecting
  client's first event is the current status, so it catches up.
- **Heartbeat:** a comment line every 20 s keeps idle connections from being cut by proxies, and detects
  clients that went away so their streams can be freed.

**Verified live with two instances** (ports 8080 and 8081, shared MongoDB and Redis): an order placed on
A reached the owner's feed on B in 252 ms, and each status change made on A reached the customer's stream
on B in 39–96 ms.

### Graceful shutdown: a bug found by a test

Spring Boot shuts down gracefully: it stops accepting requests and waits up to 30 s for in-flight ones.
An SSE stream is an in-flight request that never ends by itself, so every shutdown or deploy waited the
full 30 s. This surfaced when the integration test's JVM wouldn't exit and Surefire had to kill it.

`OrderEventStreams` is now a `SmartLifecycle` whose phase stops it **before** the web server's graceful
shutdown. It closes all streams first; clients reconnect, through the load balancer to a healthy instance,
and get the current status as their first event. Live check: with a stream open, the instance stopped
0.6 s after the shutdown request, and its log shows the streams closing before graceful shutdown began.

### Limits and next steps

- Each open stream holds one connection. With Java 21 virtual threads the request thread isn't a scarce
  resource, but a cap on streams per user would stop one client opening thousands (rate limiting step).
- Events sent while a client is disconnected aren't replayed. It gets the current state on reconnect,
  which is enough for status tracking. Full replay would need a log such as Redis Streams.
- Delivery is at-most-once over pub/sub. That's fine for UI updates, because the database stays the
  source of truth.
