# LinkForge v2: URL Shortener with Rate Limiting and Analytics (Tightened Scope)

## 1. Overview
A URL shortening service built with Java and Spring Boot. It focuses on measurable backend properties: redirect latency, rate limiter correctness under concurrency, and graceful behavior when dependencies fail.

**Principle:** a small, sharp, deployed project beats a broad, half-finished one. Ship a live MVP early, then deepen.

## 2. Tech Stack
| Layer | Choice |
|---|---|
| Language / Framework | Java 21, Spring Boot 3.x |
| Database | PostgreSQL 16 (managed free tier in production) |
| Cache / Rate limit store | Redis 7 (managed in production) |
| Migrations | Flyway |
| Auth | Spring Security + JWT |
| API docs | springdoc-openapi |
| Testing | JUnit 5, Mockito, Testcontainers |
| Load testing | k6 |
| Observability | Actuator, Micrometer; Prometheus + Grafana locally in Compose; Grafana Cloud free tier for the live demo |
| Deployment | ONE host that provides a container + managed Redis (verify current free-tier terms before choosing) |
| CI/CD | GitHub Actions |
| Frontend | Optional, only if targeting full-stack roles |

## 3. Design Decisions (resolved up front)

**D1. Short code generation (replaces the old FR-3).**
Random 7-character Base62 codes (about 3.5 trillion possibilities) with a DB unique constraint and up to 5 retries on collision. Codes are non-guessable. Document the alternative (counter plus Base62 is fast but enumerable, and needs an offset or shuffle to obfuscate) and why you rejected it.

**D2. No "same URL returns same link" rule (replaces the old FR-6).**
Each create request makes a new link. This keeps per-link analytics and custom aliases coherent. For safe client retries, support an optional `Idempotency-Key` header (stored in Redis for 24h) that returns the original response for a repeated key.

**D3. Deletion semantics.**
Soft delete keeps the row, and `short_code` stays reserved forever (unique constraint covers deleted rows). A deleted link returns 410 and its code is never reissued. Custom aliases follow the same rule.

**D4. Unique visitors (replaces the old FR-15).**
- visitor_hash = HMAC(daily-rotating secret, ip + user agent). Raw IPs are never stored.
- On each consumed click event, `PFADD hll:{linkId}:{yyyy-MM-dd} visitor_hash` (90-day TTL).
- Unique visitors for a date range = `PFCOUNT` over the daily keys. Because the secret rotates daily, the same person hashes differently each day: the union dedupes **within** a day and the range figure is an approximate sum of daily uniques, not a cross-day dedupe.
- The nightly job writes each day's final count to `link_stats_daily`.
- Document that counts are approximate (about 1% error) and that the visitor hash rotates daily, so the same person on different days is counted separately.

**D5. URL validation and SSRF.**
The server never fetches submitted URLs, so there is no server-side SSRF surface in the MVP. Validate: http/https only, max 2048 chars, no userinfo, no localhost or private/link-local IP literals, domain blocklist. If you later add link previews or safe-browsing checks, you must resolve DNS, validate the resolved IP, and refuse to follow redirects. Document this in the README.

**D6. Geo/country analytics is deferred** (needs a GeoLite2 database file, licensing, and a refresh workflow).

## 4. Functional Requirements

### 4.1 Link creation and management
- FR-1: Authenticated users create short links (D1, D2, D5).
- FR-2: Optional custom alias (3-20 chars, alphanumeric and hyphen, reserved words blocked).
- FR-3: Optional expiry. Expired links return 410.
- FR-4: Enable/disable, soft delete (D3), list own links (paginated, sortable).
- FR-5: Optional `Idempotency-Key` on create.

### 4.2 Redirect
- FR-6: `GET /{code}` returns 302 by default (301 configurable; document the browser-caching trade-off and its effect on analytics).
- FR-7: Unknown code returns 404. Expired, disabled, or deleted returns 410.
- FR-8: Lookup order is Redis, then DB, then repopulate the cache. Negative-cache unknown codes briefly (30-60s). Use TTL with jitter.
- FR-9: Click recording is asynchronous and never blocks the redirect.

### 4.3 Click pipeline (documented loss window)
- FR-10: Clicks go into a bounded in-memory queue and are flushed to the DB in batches (every 1s or 500 events).
- FR-11: On queue overflow, drop the event and increment a `clicks_dropped` metric. Never block the redirect.
- FR-12: Document the loss window: a crash can lose up to one flush interval of events. State this as an accepted trade-off. (Stretch: Redis Streams for durability.)
- FR-13: Record per click: timestamp, referrer, parsed browser/OS/device, visitor_hash.

### 4.4 Analytics
- FR-14: Per-link totals, clicks per day (date range), top referrers, browser/OS/device breakdown, unique visitors (D4).
- FR-15: A scheduled job aggregates `click_events` into `link_stats_daily` so queries stay fast.

### 4.5 Auth
- FR-16: Register and login (BCrypt). Roles USER and ADMIN.
- FR-17: Access token (15 min) plus refresh token (7 days) with **rotation and reuse detection**:
  - Every refresh token belongs to a `family_id`.
  - Using a refresh token marks it used and issues a new one in the same family.
  - If an already-used token is presented again, revoke the entire family and force re-login.
  - Store only token hashes.
- FR-18: Admin can list all links, disable abusive links, and manage the domain blocklist.

### 4.6 Rate limiting
- FR-19: Redis-backed, atomic via Lua scripts.
- FR-20: Two algorithms behind one interface (Strategy pattern): **Token Bucket** and **Sliding Window Counter**, configurable per endpoint.
- FR-21: Identity: per IP when anonymous, per user ID when authenticated.
- FR-22: Defaults (configurable): create link 20/min/user, redirect 300/min/IP, login/register 5/min/IP, analytics 60/min/user.
- FR-23: Return 429 with `Retry-After`, `X-RateLimit-Limit`, `X-RateLimit-Remaining`, `X-RateLimit-Reset`.
- FR-24: Applied through a custom `@RateLimited` annotation plus an interceptor/filter. The annotation carries `limit`, `window`, `capacity` and `algorithm`, because a token bucket cannot be expressed by `limit` + `window` alone.
- FR-25: **Redis-down policy:** redirects fail open (availability over strictness). Auth endpoints fall back to a conservative per-instance in-memory limiter. Document both decisions.
- FR-26: Client IP for per-IP limits is taken from `Forwarded`/`X-Forwarded-For` **only when the immediate peer is a configured trusted proxy**; otherwise the socket IP is used. Without this, per-IP limits are trivially bypassed by spoofing a header.

## 5. Non-Functional Requirements
- **Stateless app:** cache and limiter state live in Redis so multiple instances can run.
- **Graceful degradation:** redirects fall back to the DB when Redis is down.
- **Security:** validation, BCrypt, JWT best practices, secrets from environment variables, CORS config, HTTPS in deployment.
- **Maintainability:** layered architecture, DTOs separate from entities, global exception handler with one error format.
- **Performance:** measure honestly and publish the environment (see section 9). Do not promise a number you cannot reproduce.

## 6. Data Model
- **users**: id, email (unique), password_hash, role, created_at
- **refresh_tokens**: id, user_id, family_id, token_hash, expires_at, used_at, revoked
- **links**: id, short_code (unique, indexed), original_url, user_id, is_custom_alias, expires_at, is_active, deleted_at, created_at (NO unique index on user_id + original_url)
- **click_events**: id, link_id, clicked_at, referrer, browser, os, device_type, visitor_hash
- **link_stats_daily**: link_id, date, total_clicks, unique_visitors (PK: link_id + date)
- **blocked_domains**: id, domain, reason, created_at

Indexes: `links(short_code)`, `click_events(link_id, clicked_at)`, `click_events(clicked_at)` for the rollup scan, `link_stats_daily` PK `(link_id, stat_date)`. Day buckets are **UTC** everywhere.

## 7. API
| Method | Endpoint | Auth |
|---|---|---|
| POST | /api/v1/auth/register, /login, /refresh, /logout | Public / User |
| POST | /api/v1/links | User |
| GET | /api/v1/links | User |
| GET/PATCH/DELETE | /api/v1/links/{code} | User |
| GET | /api/v1/links/{code}/analytics | User |
| GET | /api/v1/links/{code}/analytics/breakdown | User |
| GET | /{code} | Public |
| GET | /api/v1/admin/links | Admin |
| PATCH | /api/v1/admin/links/{code}/disable | Admin |
| POST | /api/v1/admin/blocked-domains | Admin |
| GET | /actuator/health, /actuator/prometheus | Internal |

Error format: `{ timestamp, status, error, message, path }`

## 8. Phases (get something live early)

**Phase 1: Core + first deploy (weeks 1-2)**
Project setup, Docker Compose, Flyway, create + redirect (D1, D3, D5), auth with rotation and reuse detection, global error handling. Deploy a live URL now, even if basic.

**Phase 2: Cache + rate limiting (weeks 3-4)**
Redis cache-aside, negative caching, jitter. Both limiter algorithms in Lua, annotation, 429 headers, Redis-down policy.

**Phase 3: Analytics (weeks 5-6)**
Async click pipeline with a documented loss window, aggregation job, HLL unique visitors (D4), analytics endpoints.

**Phase 4: Proof (weeks 6-8)**
Concurrency tests, Testcontainers integration tests, Micrometer metrics, Grafana dashboard, k6 benchmarks, README with failure modes.

## 9. Testing and Proof (replaces the line-coverage target)
- **No duplicate codes:** N parallel creates (e.g., 1,000 across 50 threads) produce zero duplicate codes and zero unhandled errors.
- **Rate limiter correctness:** N parallel requests against one key, across **two running app instances** sharing one Redis. Sliding window: exactly `limit` are allowed. Token bucket: `capacity` immediately, then the sustained rate is `limit / window`.
- **Reuse detection:** replaying an old refresh token revokes the whole family.
- **Redis down:** redirects still succeed from the DB and recover when Redis returns.
- **Click pipeline:** redirect latency is not affected by a slow or failing DB write path.
- **Integration:** Testcontainers with real Postgres and Redis for full flows.
- **Load (k6):** 90% redirects / 10% creates, cache hit vs miss, rate-limit triggering. Record p50/p95/p99 and error rate.

**Benchmark reporting rule:** publish results as "p95 = X ms at Y rps on Z hardware" with CPU count, RAM, and whether it ran locally or in the cloud. Expect modest numbers on a laptop or free tier (likely in the low thousands of rps at best), and report what you actually measure.

## 10. README Must Include
- Architecture diagram and redirect flow
- Design decisions D1-D6 with rationale
- Benchmark table with environment
- **Failure modes:** what happens when Redis dies, when the click worker crashes or the queue overflows, when the DB is slow, and when a refresh token is replayed
- Setup: `docker compose up` runs the full stack

## 11. Definition of Done
- [ ] All endpoints work and are documented in Swagger
- [ ] All tests in section 9 pass; CI is green on every push
- [ ] Rate limiting verified across two instances
- [ ] Live URL deployed; Grafana Cloud dashboard linked
- [ ] README complete, including failure modes and benchmarks

## 12. Cut / Deferred (do not build for the MVP)
API keys, Safe Browsing integration, GeoIP country analytics, click_events partitioning, Snowflake IDs, custom domains, password-protected links, QR codes, bulk CSV upload. A React/Angular dashboard is optional and only worthwhile for full-stack roles.

## 13. Resume Bullet Template (use your real numbers)
> Built LinkForge, a URL shortening service (Spring Boot, PostgreSQL, Redis) with cache-aside redirects, a distributed token-bucket/sliding-window rate limiter using atomic Redis Lua scripts, JWT auth with refresh-token reuse detection, and async click analytics with HyperLogLog unique-visitor counts. Verified correctness under concurrency across multiple instances; measured p95 = X ms at Y rps on Z hardware (k6). CI/CD via GitHub Actions, deployed live with Prometheus/Grafana monitoring.

## 14. Interview Talking Points
- Random + retry vs counter-based codes: predictability, collisions, throughput
- Why create is not idempotent by URL, and how `Idempotency-Key` solves retries properly
- Refresh token families and reuse detection
- Why Lua for atomic rate limiting; token bucket vs sliding window
- Fail-open vs fail-closed, and why you chose differently per endpoint
- Cache stampede and cache penetration, and your mitigations
- The click pipeline's loss window as a deliberate trade-off
- HLL union across days and why the counts are approximate
- Scaling to 100x: read replicas, CDN, ID/code sharding, Redis Streams or Kafka