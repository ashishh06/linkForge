# LinkForge

A URL shortening service with distributed rate limiting and click analytics, built with Java and Spring Boot.

> **Status:** In development. Sections marked *TBD* are filled in as each phase is completed. Benchmark numbers are intentionally empty until they are measured.
>
> **This README is the canonical specification.** `projectDefnition.md` is the historical design brief and is kept for context only; if the two disagree, the README wins.

---

## 1. What is LinkForge?

LinkForge turns long URLs into short ones and redirects visitors to the original destination.

```
https://www.shop.com/products/electronics/phones/item?id=84729&utm_source=newsletter
                              becomes
                      https://linkforge.example/aB3x9Qz
```

When someone opens the short link, the service looks up the original address and sends them there. Along the way it records anonymous click data so the link owner can see how the link performs.

### Why this project exists

The idea is simple to explain, but doing it well requires solving real backend problems:

| Problem | How LinkForge addresses it |
|---|---|
| Redirects must feel instant | Redis cache-aside lookups, with PostgreSQL as the source of truth |
| Public services get abused | Distributed rate limiter (token bucket and sliding window) with atomic Redis Lua scripts |
| Concurrent requests can corrupt state | DB unique constraints, retry on collision, concurrency tests |
| Dependencies fail | Defined, documented behavior when Redis, the click worker, or the DB is degraded |
| Analytics must not slow redirects | Asynchronous click pipeline with a documented loss window |

The goal is a small, sharp, deployed project with **measurable and reproducible** properties, rather than a long feature list.

---

## 2. Tech Stack

| Layer | Technology | Purpose |
|---|---|---|
| Language | Java 21 | Application code |
| Framework | Spring Boot 3.x | REST API, dependency injection, scheduling |
| Database | PostgreSQL 16 | Permanent storage (links, users, clicks, stats) |
| Cache / limiter store | Redis 7 | Fast link lookups, rate limit state, HyperLogLog unique visitors |
| Migrations | Flyway | Versioned, repeatable schema changes |
| Auth | Spring Security + JWT | Access and refresh tokens |
| API docs | springdoc-openapi | Swagger UI |
| Testing | JUnit 5, Mockito, Testcontainers | Unit and integration tests against real Postgres and Redis |
| Load testing | k6 | Latency and throughput benchmarks |
| Observability | Spring Actuator, Micrometer, Prometheus, Grafana | Metrics and dashboards (Prometheus and Grafana run locally in Compose; a hosted Grafana is used for the live demo) |
| Containers | Docker, Docker Compose | One-command local stack |
| CI/CD | GitHub Actions | Build, test, image, deploy |
| Deployment | One host providing a container plus managed Redis | *TBD: verify current free-tier terms before choosing* |
| Frontend | Optional | Only if targeting full-stack roles |

---

## 3. Architecture

### Components

```
Visitor -> Rate limiter -> Spring Boot app -> Redis (fast) -> PostgreSQL (permanent)
                                |
                                +-> in-memory queue -> batch worker -> click_events -> nightly rollup
```

- **PostgreSQL** is the source of truth for users, links, clicks, and daily stats.
- **Redis** holds cached links, rate limit counters, and HyperLogLog sets for unique visitors.
- **The app is stateless**, so several instances can run behind a load balancer and share state through Redis.

### Redirect flow (`GET /{code}`)

1. The rate limiter checks the caller's IP. The IP is resolved from the trusted proxy chain (`Forwarded` / `X-Forwarded-For`) and accepted **only from configured proxies** — an untrusted header would let a caller choose their own rate-limit key and bypass the limit entirely.
2. Look up the code in Redis. On a hit, redirect immediately.
3. On a miss, query PostgreSQL, store the result in Redis with a TTL (plus jitter), then redirect.
4. Unknown codes are negatively cached for 30-60 seconds to blunt cache-penetration attacks.
5. A click event is placed on a bounded in-memory queue. The redirect never waits for it.

### Create flow (`POST /api/v1/links`)

1. The rate limiter checks the authenticated user.
2. Validate the URL with `java.net.URI` (never string prefix matching): http/https only, max 2048 chars, no userinfo, no localhost or private/link-local IP literals, domain blocklist. The server never dereferences the URL (D5).
3. Generate a random 7-character Base62 code and insert it. If the unique constraint rejects it, retry (up to 5 times).
4. Return the short link.

---

## 4. Design Decisions

| ID | Decision | Rationale |
|---|---|---|
| D1 | **Random Base62 codes with collision retry** | Codes are non-guessable. A counter-based scheme is faster but enumerable and needs obfuscation. |
| D2 | **Each create request makes a new link** (no "same URL returns same link" rule) | Keeps per-link analytics and custom aliases coherent. Safe client retries use an optional `Idempotency-Key` header. |
| D3 | **Soft delete; codes are never reissued** | The unique constraint on `short_code` covers deleted rows. Deleted links return 410. |
| D4 | **Unique visitors via Redis HyperLogLog, per link per day** | Visitor hash is `HMAC(daily secret, ip + user agent)`, so raw IPs are never stored. Each day writes to `hll:{linkId}:{yyyy-MM-dd}` (90-day TTL). Because the secret rotates daily, the same person hashes differently on different days: **one day's count deduplicates within that day, and a range count is the `PFCOUNT` union of daily sets — an approximate sum of daily uniques, not a cross-day dedupe.** Counts carry about 1% error. |
| D5 | **The server never fetches submitted URLs** | Removes the server-side SSRF surface. This is still an open redirect by design: mitigate with scheme allow-listing (`http`/`https` only, no `javascript:`/`data:`), reject userinfo (`http://evil.com@trusted.com`), and parse with `java.net.URI`. If link previews are added later, resolve DNS, validate the resolved IP, and refuse redirects. |
| D6 | **Geo/country analytics deferred** | Needs a GeoLite2 database file, licensing, and a refresh workflow. |

---

## 5. Functional Requirements

### Link management
- **FR-1** Authenticated users create short links (D1, D2, D5).
- **FR-2** Optional custom alias (3-20 characters, alphanumeric and hyphen; stored lowercase so `/MyLink` and `/mylink` cannot diverge). Reserved aliases are rejected: `api`, `actuator`, `admin`, `login`, `register`, `swagger-ui`, `v3`, `error`, `health`, `favicon.ico`.
- **FR-3** Optional expiry. Expired links return 410.
- **FR-4** Enable, disable, soft delete (D3), and list own links (paginated, sortable).
- **FR-5** Optional `Idempotency-Key` header on create (stored in Redis for 24 hours).

### Redirect
- **FR-6** `GET /{code}` returns 302 by default (301 configurable; the browser-caching trade-off is documented).
- **FR-7** Unknown code returns 404. Expired, disabled, or deleted returns 410.
- **FR-8** Lookup order is Redis, then DB, then repopulate cache. Negative caching and TTL jitter apply.
- **FR-9** Click recording is asynchronous and never blocks the redirect.

### Click pipeline
- **FR-10** Clicks go to a bounded in-memory queue and are flushed to the DB in batches (every 1 second or 500 events).
- **FR-11** On overflow, drop the event and increment a `clicks_dropped` metric. Never block the redirect.
- **FR-12** The loss window (up to one flush interval on a crash) is a documented, accepted trade-off.
- **FR-13** Per click: timestamp, referrer, parsed browser, OS, device type, and visitor hash.

### Analytics
- **FR-14** Per link: total clicks, clicks per day over a date range, top referrers, browser/OS/device breakdown, unique visitors (D4).
- **FR-15** A scheduled job aggregates `click_events` into `link_stats_daily`.

### Authentication
- **FR-16** Register and log in with BCrypt-hashed passwords. Roles: `USER`, `ADMIN`.
- **FR-17** Access token (15 min) and refresh token (7 days) with **rotation and reuse detection**. Each refresh token belongs to a family. If a used token is presented again, the whole family is revoked. Only token hashes are stored.
- **FR-18** Admins can list all links, disable abusive links, and manage the domain blocklist.

### Rate limiting
- **FR-19** Redis-backed and atomic via Lua scripts.
- **FR-20** Two algorithms behind one interface (Strategy pattern): **Token Bucket** and **Sliding Window Counter**, configurable per endpoint.
- **FR-21** Identity is per IP when anonymous and per user ID when authenticated.
- **FR-22** Configurable defaults:

  | Endpoint | Default limit |
  |---|---|
  | Create link | 20 per minute per user |
  | Redirect | 300 per minute per IP |
  | Login / register | 5 per minute per IP |
  | Analytics | 60 per minute per user |

- **FR-23** Rejected requests return 429 with `Retry-After`, `X-RateLimit-Limit`, `X-RateLimit-Remaining`, and `X-RateLimit-Reset`.
- **FR-24** Applied through a custom `@RateLimited(limit, window, capacity, algorithm)` annotation and a Spring interceptor.
- **FR-25** **Redis-down policy:** redirects fail open (availability over strictness). Auth endpoints fall back to a conservative per-instance in-memory limiter.

---

## 6. Non-Functional Requirements

- **Stateless application.** Cache and limiter state live in Redis so multiple instances can run.
- **Graceful degradation.** Redirects fall back to the database when Redis is down.
- **Security.** Input validation, BCrypt, JWT best practices, secrets from environment variables, CORS configuration, HTTPS in deployment, and client-IP resolution that trusts only configured proxies (otherwise per-IP limits are spoofable).
- **Maintainability.** Layered architecture, DTOs separate from entities, one global exception handler and error format.
- **Honest performance.** Results are measured and published with their environment. No number is promised that cannot be reproduced.

---

## 7. Data Model

Defined in `V1__init_schema.sql` and applied automatically by Flyway at startup.

| Table | Purpose |
|---|---|
| `users` | Accounts, password hashes, roles |
| `refresh_tokens` | Hashed refresh tokens with `family_id` and `used_at` for reuse detection |
| `links` | Short codes and destinations. `short_code` is unique across all rows including soft-deleted ones. There is deliberately no unique index on `(user_id, original_url)`. |
| `click_events` | One row per click (no raw IPs) |
| `link_stats_daily` | Nightly rollups per link per **UTC day** (`stat_date`): total clicks and approximate unique visitors. `click_events` stays authoritative; the rollup is a materialised cache. |
| `blocked_domains` | Domains that cannot be shortened |

Notes:

- Day boundaries are **UTC** everywhere — the rollup job, the HLL keys, and the analytics endpoints.
- "Clicks today" is served from `click_events`; completed days come from the rollup. The analytics endpoints stitch the two at the UTC day boundary, so a query never misses or double-counts the current day.
- `link_stats_daily.unique_visitors` is written **once** per day: the Redis HLL keys expire after 90 days, so a late re-run cannot recompute it.
- `link_stats_daily` is keyed `(link_id, stat_date)`, so the primary key already serves range queries; no extra index is needed.
- Refresh-token rotation must be a single atomic `UPDATE ... WHERE used_at IS NULL RETURNING`, not a read-then-write, or two concurrent refreshes fork the token family.

---

## 8. Rate Limiter Scripts

Two Lua scripts run inside Redis so that checking and updating a counter is a single indivisible step. This prevents race conditions, including across multiple app instances.

| File | Algorithm | Behavior |
|---|---|---|
| `token_bucket.lua` | Token bucket | Allows short bursts, refills steadily |
| `sliding_window.lua` | Sliding window counter | Smoother and stricter, estimated from the current and previous windows |

Both use the Redis server clock (so app instances cannot disagree about time) and return `{allowed, remaining, retry_after_ms, reset_ms}` in **milliseconds**. The Java layer converts: `Retry-After: ceil(retry_after_ms / 1000)` seconds and `X-RateLimit-Reset: <epoch seconds>` (now + `reset_ms`).

A token bucket cannot be configured from `limit` + `window` alone, so the annotation carries both a window and a burst capacity:

```java
@RateLimited(limit = 20, window = 60, capacity = 20, algorithm = TOKEN_BUCKET)
```

For `SLIDING_WINDOW`, `capacity` is ignored and the algorithm uses `limit` requests per `window` seconds. Both scripts validate their arguments and return an error rather than a Redis `inf`/NaN on a misconfiguration.

---

## 9. API Overview

| Method | Endpoint | Auth |
|---|---|---|
| POST | `/api/v1/auth/register`, `/login`, `/refresh`, `/logout` | Public / User |
| POST | `/api/v1/links` | User |
| GET | `/api/v1/links` | User |
| GET, PATCH, DELETE | `/api/v1/links/{code}` | User |
| GET | `/api/v1/links/{code}/analytics` | User |
| GET | `/api/v1/links/{code}/analytics/breakdown` | User |
| GET | `/{code}` | Public |
| GET | `/api/v1/admin/links` | Admin |
| PATCH | `/api/v1/admin/links/{code}/disable` | Admin |
| POST | `/api/v1/admin/blocked-domains` | Admin |
| GET | `/actuator/health`, `/actuator/prometheus` | Internal |

Error format: `{ "timestamp", "status", "error", "message", "path" }`

Interactive documentation is served through Swagger UI once the app is running.

---

## 10. Project Structure (planned)

```
linkforge/
├── src/main/java/com/yourname/linkforge/
│   ├── config/          security, redis, async, openapi
│   ├── controller/
│   ├── service/
│   ├── repository/
│   ├── entity/
│   ├── dto/
│   ├── ratelimit/       annotation, interceptor, strategies
│   ├── analytics/       click queue, worker, aggregation job
│   ├── security/        jwt, filters
│   ├── exception/
│   └── util/            base62, url validator
├── src/main/resources/
│   ├── db/migration/    V1__init_schema.sql
│   ├── scripts/         token_bucket.lua, sliding_window.lua
│   └── application.yml
├── src/test/java/...
├── loadtest/            k6 scripts
├── docker/              prometheus.yml, grafana dashboards
├── docker-compose.yml
├── Dockerfile
├── .github/workflows/ci.yml
└── README.md
```

---

## 11. Getting Started

*TBD: finalized once the Docker Compose file exists.* The target experience is:

```bash
git clone <repo-url>
cd linkforge
docker compose up
```

This should start the app, PostgreSQL, Redis, Prometheus, and Grafana with no manual steps. The API will be at `http://localhost:8080` and Swagger UI at `http://localhost:8080/swagger-ui.html`.

---

## 12. Testing and Proof

Line coverage is deliberately not the target. These tests demonstrate correctness:

| Test | What it proves |
|---|---|
| Parallel creates (e.g. 1,000 across 50 threads) | Zero duplicate codes and zero unhandled errors |
| Parallel requests against one limiter key, across **two running app instances** sharing one Redis | Sliding window: exactly `limit` are allowed. Token bucket: `capacity` are allowed instantly, then the sustained rate converges to `limit / window`. |
| Replay of an old refresh token | The whole token family is revoked |
| Redis stopped mid-test | Redirects still succeed from the DB and recover when Redis returns |
| Slow or failing click write path (DB latency injected through a fault flag and a test double) | Redirect p95 stays within the no-fault baseline |
| Testcontainers integration tests | Full flows work against real Postgres and Redis |
| k6 load test (90% redirects, 10% creates) | Latency percentiles and error rate under load |

---

## 13. Benchmarks

*TBD. To be filled in after load testing.* Results will be reported in this form:

> p95 = **X ms** at **Y requests/sec** on **Z hardware** (CPU count, RAM, local or cloud)

| Scenario | p50 | p95 | p99 | Req/sec | Error rate | Environment |
|---|---|---|---|---|---|---|
| Redirect, cache hit | TBD | TBD | TBD | TBD | TBD | TBD |
| Redirect, cache miss | TBD | TBD | TBD | TBD | TBD | TBD |
| Create link | TBD | TBD | TBD | TBD | TBD | TBD |

---

## 14. Failure Modes

*TBD: confirmed against real tests as they are written.* Planned behavior:

| Failure | Behavior |
|---|---|
| Redis goes down | Redirects fall back to PostgreSQL (slower, still working). Redirect rate limiting fails open. Auth endpoints use a per-instance in-memory limiter. |
| Click worker crashes | Redirects continue. Up to one flush interval of click events may be lost. |
| Click queue overflows | Events are dropped and counted in `clicks_dropped`. Redirects never block. |
| Database is slow | Popular links keep being served from Redis. Creates and cache misses slow down. |
| Refresh token replayed | The entire token family is revoked and the user must log in again. |
| Client spoofs `X-Forwarded-For` | Ignored unless the request comes from a configured trusted proxy, so the caller cannot pick their own rate-limit key. |
| Rate limiter misconfigured (limit/refill ≤ 0) | The Lua script returns an explicit error instead of a Redis `inf`; the endpoint fails with 500 rather than silently allowing everything. |

---

## 15. Development Plan

| Phase | Weeks | Scope |
|---|---|---|
| 1. Core + first deploy | 1-2 | Setup, Compose, Flyway, create and redirect, auth with rotation and reuse detection, error handling, **a live URL** |
| 2. Cache + rate limiting | 3-4 | Redis cache-aside, negative caching, both limiter algorithms, annotation, 429 headers, Redis-down policy |
| 3. Analytics | 5-6 | Async click pipeline, aggregation job, HyperLogLog unique visitors, analytics endpoints |
| 4. Proof | 6-8 | Concurrency tests, Testcontainers, metrics, Grafana, k6 benchmarks, finish this README |

Estimated effort is 50-70 focused hours, which is about 6-8 calendar weeks at 10-15 hours per week. These are rough estimates.

---

## 16. Definition of Done

- [ ] All endpoints work and are documented in Swagger
- [ ] All tests in section 12 pass and CI is green on every push
- [ ] Rate limiting verified across two app instances
- [ ] Live URL deployed and a hosted Grafana dashboard linked
- [ ] README complete, including failure modes and measured benchmarks

---

## 17. Out of Scope (deferred)

API keys, Safe Browsing integration, GeoIP country analytics, `click_events` partitioning, Snowflake-style IDs, custom domains, password-protected links, QR codes, bulk CSV upload. A React or Angular dashboard is optional.

---

## 18. License

*TBD.*