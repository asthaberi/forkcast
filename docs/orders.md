# Order placement design

`POST /api/v1/orders` turns the user's cart into an order. Placing an order has to be correct even when
the client misbehaves (double taps, retries after a timeout) and when requests race each other.

## Checks before an order is created

| Rule | Response |
|---|---|
| Cart has items | `409 CART_EMPTY` |
| Every item is still available | `409 CART_HAS_UNAVAILABLE_ITEMS` with `unavailableItems` |
| Restaurant is open now | `409 RESTAURANT_CLOSED` with `opensAt` |
| Address is within the serving radius *right now* (3 km at peak hours, 5 km otherwise) | `422 OUT_OF_DELIVERY_RANGE` with `distanceKm`, `maxDistanceKm` |

Items and prices come from the server-side cart, re-priced from the current menu; the request only carries
the delivery address. The order stores a **snapshot** of names and prices: unlike the cart, it never changes
when the menu does.

## Atomic: one MongoDB transaction

Creating the order and emptying the cart happen in **one multi-document transaction**
(`TransactionTemplate` + `MongoTransactionManager`). Otherwise a crash between the two writes would leave an
order whose cart wasn't emptied (so the user might order it again) or an emptied cart with no order.

MongoDB only supports multi-document transactions on a **replica set**, so local MongoDB runs as a
single-node replica set (`--replSet rs0` in `docker-compose.yml`), and the integration tests use
Testcontainers' `MongoDBContainer.withReplicaSet()`.

The cart is cleared through its `@Version` check, without a retry. If the cart changed after checkout read
it, the clear fails and the **whole transaction rolls back**, so an order is never built from a stale cart.

## Idempotent: the `Idempotency-Key` header

The client generates a unique key (e.g. a UUID) per checkout and sends it with every attempt.

- The first request creates the order: `201 Created`.
- Any repeat with the same key returns the **same order**: `200 OK` and `Idempotent-Replayed: true`.
- A unique index on `(userId, idempotencyKey)` guarantees at most one order per key, even when the
  repeats arrive at the same instant.

The header is required, so clients can't skip it. Keys are scoped per user. A repeated key returns the
original order even if the request body differs.

## Retrying conflicts, and only conflicts

```
loop (max 3 attempts):
    if an order already exists for (user, key): return it          ← idempotency check
    try: run transaction { read cart → validate → insert order → clear cart (version-checked) }
    catch concurrency conflict: back off, retry
    other errors: propagate unchanged
```

What counts as a conflict:

- `DuplicateKeyException`: a request with the same key committed first.
- `OptimisticLockingFailureException`: the cart changed after it was read.
- `TransactionSystemException`: the commit outcome is unknown. Retrying is safe because the idempotency
  check runs first and finds the order if the commit actually succeeded.
- Any MongoDB error labelled **`TransientTransactionError`**: MongoDB's documented signal to retry the
  whole transaction. The common case is `WriteConflict` (code 112), when two transactions write the same
  document. Spring translates it into a *non-transient* `DataIntegrityViolationException`, so the code checks
  the driver exception's label rather than Spring's exception type.

### Two bugs the integration tests caught

Mocked unit tests passed while both of these were broken; only running against a real replica set exposed them.

1. **An over-broad retry hid a real error.** The first version caught every `DataAccessException`. When
   transactions were misconfigured, it retried 3 times and told the user "your cart changed". It now
   retries only the conflicts above, and a test checks that other errors propagate unchanged after one attempt.
2. **The write conflict wasn't recognised.** Concurrent checkouts failed with `WriteConflict`, which Spring
   doesn't classify as transient. Fixed by checking MongoDB's `TransientTransactionError` label.

### Results (real MongoDB, `OrderPlacementIntegrationTest`)

| Scenario | Result |
|---|---|
| 10 simultaneous requests, same key | exactly 1 order; 1 × created, 9 × replayed, 0 errors |
| 5 simultaneous checkouts of one cart with different keys (phone + laptop) | exactly 1 order; the rest get `CART_EMPTY` / `CONCURRENT_MODIFICATION` |
| Failure injected after the order insert | transaction rolled back: no order, cart untouched |
| Menu price changed after ordering | order keeps the price paid |

Stable across repeated runs; a single run retried 13 real write conflicts. Over HTTP against the running app,
10 parallel taps with one key also produced one order (one `201`, nine `200` replays).

## Other endpoints

- `GET /api/v1/orders?page=0&size=10`: the user's orders, newest first, served by a
  `(userId, placedAt desc)` index. Returned as a stable `PageResponse`, not Spring's `Page`.
- `GET /api/v1/orders/{id}`: someone else's order id returns `404`, not `403`, so it doesn't reveal that the order exists.
- `POST /api/v1/orders/{id}/cancel`: allowed only while `PLACED` (`409 ORDER_NOT_CANCELLABLE` afterwards).
  Every status change is appended to `statusHistory`; `@Version` guards against concurrent status changes.

The restaurant-side lifecycle (accept → preparing → out for delivery → delivered) and live tracking come
with the order state machine in phase 2.
