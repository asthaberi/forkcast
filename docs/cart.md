# Cart design

## Rules

- **One cart per user**, enforced by a unique index on `userId`.
- **One restaurant per cart.** Adding an item from another restaurant returns `409 CART_RESTAURANT_MISMATCH`
  with `currentRestaurantId` / `currentRestaurantName`, so the app can ask "Replace your cart?".
  Re-sending the request with `?replace=true` clears the cart and adds the item.
- **At most 20 of one item** (`422 QUANTITY_LIMIT_EXCEEDED`). Setting a quantity to 0 removes the item;
  removing the last item frees the cart for any restaurant.
- **Sold-out items** can't be added (`409 ITEM_UNAVAILABLE`). If an item sells out or is removed after
  being added, it stays in the cart marked `available: false` and isn't charged.

The rules live on the `Cart` document itself (`addItem`, `setQuantity`, ...), so they're unit tested
without Spring or a database.

## Prices are never stored in the cart

A cart line holds only `menuItemId`, `quantity` and the dish name (used to label the line if the dish
disappears from the menu). Every response re-prices the cart against the **current** menu:

```
subtotal     = Σ current price × quantity   (available items only)
delivery fee = ₹30, free when subtotal ≥ ₹499
GST          = 5% of subtotal, rounded half-up to paise
total        = subtotal + delivery fee + GST
```

So a price change shows up immediately and a client can never submit its own price. The menu is read
through `MenuService.getMenu`, which is cached in Redis and evicted on menu changes. Pricing is
therefore always current and costs no extra database queries. All amounts are `BigDecimal` (exact decimal
arithmetic, unlike `double`), and the charges are configurable under `forkcast.pricing.*`.

## Concurrency: optimistic locking with retry

Two requests updating the same cart at once (two tabs, a double-tap) each do read → modify → save.
Without protection, the second save overwrites the first, and that update is silently lost.

`Cart` has a `@Version` field. Spring Data includes it in every update's filter
(`{_id: ..., version: 3}`) and increments it, so a save based on a stale read matches nothing and throws
`OptimisticLockingFailureException`. `CartService.update` catches it, **re-reads the cart and re-applies
the change** (so rules like the quantity limit are checked against current data), and retries up to
5 times with a small random, growing back-off. If it still conflicts it returns
`409 CONCURRENT_MODIFICATION` instead of guessing. The same retry handles two "first add" requests racing
to create a cart (`DuplicateKeyException` from the unique `userId` index).

**Why optimistic, not pessimistic locking?** Conflicts on one user's cart are rare, so taking a lock on
every request would cost more than the occasional retry. MongoDB also has no `SELECT ... FOR UPDATE`.

**Alternative considered:** atomic `$inc` updates avoid read-modify-write entirely, but rules like "one
restaurant per cart" and "max 20" would have to be expressed as update filters. Keeping the rules in Java
is clearer and easier to test.

### Experiment

`benchmark/cart_concurrency.py` fires N simultaneous "add 1 item" requests at one brand-new user's cart
(so they also race to create it) and checks the final quantity equals the number of `200` responses.

| | 20 simultaneous requests × 5 rounds | 3 simultaneous requests × 10 rounds |
|---|---|---|
| **With `@Version` + retry** | 79 / 100 succeeded, 21 got `409`, **0 lost updates** | 30 / 30 succeeded, **0 lost** |
| **Without `@Version`** (experiment) | 100 / 100 returned `200`, but carts held only 2–4 items: **86 lost updates** | **3 lost** |

Without locking, every request is told it succeeded while most of the writes vanish. With locking, the
system never lies: under extreme contention (20 writers on one document at the same instant) some
requests are rejected with a clear, retryable `409`, and at realistic contention the retries absorb
every conflict.

## Identity

Cart endpoints need a customer's JWT (`Authorization: Bearer <token>`). The user id comes from the
token's subject through `@CurrentUser`. See [security.md](security.md).
