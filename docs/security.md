# Authentication and authorization

## Overview

- **Stateless JWT bearer tokens.** `POST /api/v1/auth/login` returns an access token. Clients send it on
  every request as `Authorization: Bearer <token>`. The server keeps no session.
- **Spring Security's OAuth2 resource server** validates every token: signature, expiry and not-before
  (with 60 s clock-skew allowance), and issuer. There's no hand-written JWT filter, so less custom
  security code can go wrong.
- **Three roles:** `CUSTOMER`, `RESTAURANT_OWNER` and `ADMIN`, plus a per-restaurant **ownership** check for menu edits.

## Tokens

| | |
|---|---|
| Algorithm | HS256 (HMAC-SHA256) with a ≥ 256-bit secret from `JWT_SECRET`; startup fails if it's shorter |
| Claims | `sub` (user id), `roles`, `iss`, `iat`, `exp`. No email or name, because a JWT payload is only base64-encoded and anyone holding the token can read it |
| Lifetime | 60 minutes |

**Why HS256?** This one service both issues and verifies tokens, so a shared secret is enough. If other
services ever need to verify tokens, switch to RS256: they'd only get the public key, and only this
service could sign.

**Trade-off of stateless tokens:** authorizing a request needs no database lookup, but a token can't be
revoked before it expires. If a user's role is changed, the old token keeps its old roles for up to an
hour. The short lifetime bounds that. Refresh tokens (long-lived, stored server-side, revocable) plus
shorter access tokens are the usual next step.

## Passwords

- Hashed with **BCrypt** through `DelegatingPasswordEncoder`, stored as `{bcrypt}$2a$10$...`. The prefix
  lets the algorithm be upgraded later without invalidating existing hashes.
- BCrypt only uses the first **72 bytes** of a password, so longer passwords are rejected
  (`422 PASSWORD_TOO_LONG`), not silently truncated. The limit is checked in **bytes**, not characters:
  30 × "€" is 30 characters but 90 bytes.
- Emails are trimmed and lowercased before storage. A unique index stops duplicate accounts even when two
  sign-ups race (`409 EMAIL_ALREADY_REGISTERED`).

### Login doesn't reveal which emails exist

An unknown email and a wrong password return the **same** `401 INVALID_CREDENTIALS` with the same
message. For an unknown email the service still runs one BCrypt comparison against a dummy hash, so
the response time doesn't give it away either. Sign-up does say an email is taken; that's a deliberate
usability trade-off most sites make. Login is where enumeration matters, because it's where password
guessing happens. Login rate limiting is a separate step.

## Authorization rules

| Endpoint | Who |
|---|---|
| `POST /api/v1/auth/register`, `/login` | anyone |
| `GET /api/v1/restaurants/**` (search, details, menus) | anyone |
| `POST /api/v1/restaurants` | owner (who becomes its owner) or admin |
| `POST/PUT/DELETE /api/v1/restaurants/{id}/menu/items/**` | **that restaurant's** owner, or admin |
| `/api/v1/cart/**`, `/api/v1/orders/**` | customer |
| `GET /api/v1/users/me` | any signed-in user |
| `/actuator/health`, `/actuator/info` | anyone |
| other `/actuator/**` (metrics) | admin |
| anything else | signed-in (deny by default) |

URL rules in `SecurityConfig` check the **role**. The **ownership** check ("owners edit only their own
restaurants") is object-level, so it runs per request in `RestaurantAccess.canManage`, called from
`@PreAuthorize` on the menu endpoints. For an owner, an unknown restaurant id also returns `403`,
so owners can't probe which ids exist.

Self-registration offers only `CUSTOMER` or `RESTAURANT_OWNER`. `ADMIN` isn't a value of the sign-up
`accountType` enum, so it can't be requested at all.

## Errors

Security rejections happen in servlet filters, before Spring MVC's exception handling, so
`ProblemDetailsSecurityHandlers` writes the same Problem Details JSON as the rest of the API:

- `401 Unauthenticated`: no token, or an invalid, expired or tampered one. Includes the standard
  `WWW-Authenticate: Bearer ...` header (with `error="invalid_token"` when a token was rejected).
- `403 Forbidden`: valid token, but the role or ownership doesn't allow the request.

`/error` is explicitly public; otherwise Spring Boot's error forwarding would turn every error into a 401.

## CSRF and sessions

CSRF protection is disabled and sessions are `STATELESS`. CSRF works by abusing cookies the browser
attaches automatically. A bearer token has to be added to each request by the client's own code,
so another site can't forge an authenticated request.

## Code changes

`@CurrentUser` used to read a temporary `X-User-Id` header; now `CurrentUserArgumentResolver` reads the
verified token's subject. Because controllers depended only on the annotation, **no controller changed**.

## Demo accounts (local development only)

Created on startup when seeding is enabled, from `forkcast.seed.users` in `application.yml`:

| Email | Role | Notes |
|---|---|---|
| `admin@forkcast.example` | ADMIN | |
| `owner@forkcast.example` | RESTAURANT_OWNER | owns *Dosa Junction* |
| `customer@forkcast.example` | CUSTOMER | |

Their passwords are in `application.yml` (overridable with `SEED_*_PASSWORD`). Disable seeding
(`SEED_ENABLED=false`) and set `JWT_SECRET` anywhere other than your own machine.

## Tests

- `TokenServiceTest`: claims, plus rejection of expired tokens, tokens with a forged payload (ADMIN
  role swapped in), tokens signed with another key, and short secrets.
- `AuthServiceTest`: hashing, email normalization, duplicate sign-ups (including the unique-index race),
  the 72-byte limit, and identical failures for unknown email vs wrong password.
- `AuthFlowIntegrationTest` (Testcontainers MongoDB, real filter chain): register → login → use token;
  missing, tampered and expired tokens → 401; owner A can't edit owner B's menu but an admin can;
  `accountType: ADMIN` is rejected; metrics need admin.
- Controller slice tests load the real `SecurityConfig` (`@WithApiSecurity`) and use
  `spring-security-test`'s `jwt()`, so they check the production 401/403 rules too.
