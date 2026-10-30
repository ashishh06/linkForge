# LinkForge — Project Context

> **Purpose of this file:** hand-off document. A new session/agent should read this
> first, then `README.md`. It records what is built, what is decided, and what comes
> next so work can continue without re-deriving the plan.

Last updated: **Phase 1 code complete; live deploy pending** (2026-10-28)

---

## 1. What this project is

A URL shortener with distributed rate limiting and click analytics, built with
Java 21 + Spring Boot 4.1.x, PostgreSQL 16, Redis 7, Flyway, JWT, Testcontainers,
k6, Prometheus/Grafana, Docker and GitHub Actions.

The point is **not** feature count. The point is a small, deployed service with
properties that can be measured and defended in an interview: redirect p95,
rate-limiter correctness under concurrency across instances, and defined behaviour
when Redis, the click worker or the DB degrades.

**Canonical spec: `README.md`.** `projectDefnition.md` is the original design brief,
kept for context only; it was superseded and partially corrected. If they disagree,
the README wins.

---

## 2. Locked decisions (do not re-litigate without a reason)

| ID | Decision | Why |
|---|---|---|
| D1 | Random 7-char Base62 codes + DB unique constraint + 5 collision retries | Non-guessable. Counter+Base62 is faster but enumerable. |
| D2 | Every create makes a new link; no "same URL returns same link" | Keeps per-link analytics and aliases coherent. Retries use `Idempotency-Key`. |
| D3 | Soft delete; a `short_code` is never reissued (unique covers deleted rows) | Deleted links return 410. |
| D4 | Unique visitors = Redis HLL **per link per day**, visitor hash = `HMAC(daily secret, ip+ua)` | Raw IPs never stored. Daily secret rotation means **no cross-day dedupe** — a range count is an approximate sum of daily uniques. Do not claim otherwise. |
| D5 | The server never fetches submitted URLs | Removes SSRF. Still an open redirect by design: allow-list `http`/`https`, reject userinfo, parse with `java.net.URI`. |
| D6 | GeoIP/country analytics deferred | Needs GeoLite2 + licensing + refresh workflow. |
| — | Spring Boot **4.1.x**, Java **21**, Maven wrapper | 3.5 is out of OSS support. |
| — | **No Lombok** | Records for DTOs, plain classes for entities. |
| — | JPA for CRUD, **`JdbcClient`** for batch click inserts + analytics SQL | Keeps the interesting queries honest. |
| — | Client IP: `server.forward-headers-strategy=none`; **own `ClientIpResolver`** with a trusted-proxy allowlist | Boot's `framework` strategy trusts every `X-Forwarded-For`, which lets a caller pick their own rate-limit key. |
| — | Rate-limit headers: scripts return **milliseconds**; Java converts to `Retry-After` seconds and `X-RateLimit-Reset` epoch seconds | |
| — | **Jackson 3** (`tools.jackson.databind.ObjectMapper`) | Boot 4 auto-configures Jackson 3. Jackson 2 classes are on the classpath transitively but there is **no Jackson 2 `ObjectMapper` bean**; injecting `com.fasterxml.jackson.databind.ObjectMapper` fails at startup. |
| — | Access tokens: **HS256 via `spring-security-oauth2-jose` (Nimbus)** | `JwtEncoder`/`JwtDecoder` are Boot-managed. Secret must be ≥32 UTF-8 bytes; access TTL 15 min. |
| — | Phase 1 already issues/parses access JWTs | Needed to protect `POST /api/v1/links`. Phase 2 adds refresh tokens, rotation/reuse detection, roles and method security on top; it does not replace the token format. |
| — | Collision retry inserts in **`REQUIRES_NEW`** transactions (`LinkInserter`) | A unique-constraint violation marks the current transaction rollback-only, so retrying in the same transaction is impossible. |

---

## 3. Current repository state

```
linkforge/
├── pom.xml                    Boot 4.1.2 parent, Java 21, spring-security-oauth2-jose
├── mvnw, mvnw.cmd, .mvn/      Maven wrapper
├── docker-compose.yml         postgres 16 + redis 7 (app service added in P8)
├── .env.example
├── README.md                  canonical spec
├── projectDefnition.md        historical brief
├── context.md                 this file
└── src/
    ├── main/java/com/linkforge/
    │   ├── LinkforgeApplication.java        @ConfigurationPropertiesScan
    │   ├── config/
    │   │   ├── SecurityConfig.java          stateless, JWT filter, JSON 401/403
    │   │   ├── OpenApiConfig.java
    │   │   ├── DevDataSeeder.java           local profile only
    │   │   └── properties/LinkforgeProperties.java
    │   ├── controller/                      AuthController, LinkController, RedirectController
    │   ├── dto/                             auth/, link/, error/ApiError
    │   ├── entity/                          User, Link, BlockedDomain, Role
    │   ├── repository/                      User, Link, BlockedDomain (JPA)
    │   ├── security/                        JwtService, JwtAuthenticationFilter, AuthenticatedUser, IssuedToken
    │   ├── service/                         AuthService, LinkService, LinkInserter, RedirectService
    │   ├── util/                            Base62, ShortCodeGenerator, UrlValidator
    │   ├── exception/                       ApiException hierarchy, GlobalExceptionHandler, ApiErrorWriter
    │   ├── ratelimit/, analytics/           package placeholders (P5/P6)
    │   └── <package-info.java per package>
    ├── main/resources/
    │   ├── application.yml                  linkforge.* incl. jwt
    │   ├── application-local.yml
    │   ├── db/migration/V1__init_schema.sql
    │   └── scripts/token_bucket.lua, sliding_window.lua
    └── test/java/com/linkforge/
        ├── *Test.java                       unit tests (Base62, URL, JWT, services)
        ├── AuthLinkFlowIT.java              register→login→create→302, 401/404/410/400
        ├── LinkCreationConcurrencyIT.java   1,000 creates / 50 threads
        ├── LinkforgeApplicationIT.java      context load
        └── support/                         IntegrationTestBase, TestProperties
```

### Phase 0 verification (what was actually run)

| Check | Result |
|---|---|
| `./mvnw -B -DskipTests package` | BUILD SUCCESS |
| `./mvnw -B verify` (Testcontainers Postgres 16 + Redis 7) | 1 test, 0 failures; Flyway applied `1 - init schema` |
| `docker compose up -d` | postgres + redis both `healthy` |
| App boot with `local` profile | `Started LinkforgeApplication`, no WARN/ERROR in the log |
| `/actuator/health` | `UP` (liveness + readiness groups) |
| `/actuator/prometheus` | 200, 178 metric lines |
| `/v3/api-docs` and `/swagger-ui/index.html` | 200, title "LinkForge API" |
| `/api/v1/links` with no token | 403 at P0 (locked down as intended); P1 now returns the documented JSON 401 |

### Phase 1 verification (what was actually run)

| Check | Result |
|---|---|
| `./mvnw -B test` | 35 unit tests, 0 failures; **no Docker needed** |
| `./mvnw -B verify` | 45 tests (35 unit + 10 integration), 0 failures; Testcontainers Postgres 16 + Redis 7; Flyway V1 applied |
| `LinkCreationConcurrencyIT` | 1,000 creates across 50 threads → 1,000 distinct codes, zero unhandled errors, row count exactly +1,000 (≈5 s) |
| Live app (`local` profile) | register → login → create → `GET /{code}` returned **302** with the exact destination in `Location` |
| Auth/error semantics | no token → 401 JSON; unknown code → 404; expired/disabled/deleted → 410; `javascript:` / loopback / userinfo → 400 |
| Swagger | `/v3/api-docs` and `/swagger-ui/index.html` = 200 |
| `docker compose up -d` | postgres + redis healthy; V1 already applied |
| **Outstanding** | a public live URL is not deployed yet — needs a hosting choice and credentials |

**Phase 1 implementation notes:**
* `LinkInserter.insert` is a separate bean with `@Transactional(REQUIRES_NEW)`. A duplicate
  `short_code` rolls back that attempt cleanly, then `LinkService` retries up to
  `max-collision-retries` (5). Non-collision integrity errors are rethrown, not retried.
* `UrlValidator` rejects trailing-dot host tricks (`localhost.`, `evil.com.`), decimal
  IPv4 (`2130706433`), IPv4 private/loopback/link-local/CGNAT ranges, IPv6 loopback/ULA/
  link-local and embedded IPv4, plus userinfo.
* `SecurityConfig` permits exactly a single-segment `GET /{code}` (and HEAD). Multi-segment
  `/api/v1/**` paths still require authentication.
* `local` profile seeds `dev@linkforge.local` / `devpassword`.


### Verified before Phase 0 (keep these properties)
Both Lua scripts and the schema were tested against **real Redis 7 and Postgres 16**:

* Token bucket: 5-of-6 at capacity 5, refill after 1.2 s, invalid config rejected.
* Sliding window: exactly `limit` allowed, `retry_after_ms` is an **exact solve across
  the window rollover** (the naive `window - elapsed` caused a 429 retry storm), and a
  honoured `Retry-After` never produces another 429.
* Sliding window overshoot in a true rolling window is ~1 % (101 at limit 100) — this
  is inherent to the two-window approximation and is documented, not a bug.
* Schema: 6 valid inserts succeed, 11 invalid inserts rejected by the intended
  constraint (case-insensitive email, charset, lowercase alias, scheme, expiry, FK).

**Do not regress:** email is stored lowercase (`CHECK`), `link_stats_daily` uses
`stat_date` (UTC day), `click_events` has both `(link_id, clicked_at)` and
`(clicked_at)`, and the refresh-token rotation must be one atomic
`UPDATE ... WHERE used_at IS NULL RETURNING`.

---

## 4. Conventions

* Package root `com.linkforge`. Config prefix `linkforge.*` (never `app.*`).
* Controllers take/return records only. Entities never leave the service layer.
* Errors use one shape: `{timestamp, status, error, message, path}`.
* Time: UTC everywhere (`spring.jackson.time-zone`, `hibernate.jdbc.time_zone`,
  analytics day buckets).
* Lua is loaded with `new ClassPathResource("scripts/<name>.lua")`.
* Tests: `*Test` = unit (surefire), `*IT` = Testcontainers (failsafe), so
  `./mvnw verify` runs both. Integration tests extend `support/IntegrationTestBase`.
* API base: `/api/v1/...`; redirect is `GET /{code}`. Reserved aliases are listed in
  `application.yml` and must include `api`, `actuator`, `admin`, `login`, `register`,
  `swagger-ui`, `v3`, `error`, `health`, `favicon.ico`.

---

## 5. Phases

Status: **0 done, 1 code-complete (live deploy pending)**, 2–8 pending. Each phase must
end runnable, tested and (from P1) deployed. Tests are written inside the phase, never
deferred.

### Phase 0 — Bootstrap ✅
Maven project, Boot 4.1.2, Java 21, docker-compose (Postgres 16 + Redis 7),
Flyway wired to `classpath:db/migration`, YAML config, `.env.example`, package
skeleton, Testcontainers base, actuator health.
**Exit:** `./mvnw verify` green; `docker compose up -d` + app boots; V1 applies;
`/actuator/health` = UP. ✅ verified (see §3).

### Phase 1 — Walking skeleton + first deploy ✅ (code + local verification)
`users`/`links`/`blocked_domains` entities + repositories, register/login (access
token only), `POST /api/v1/links`, `GET /{code}` 302, `Base62`,
`ShortCodeGenerator`, `UrlValidator` (URI + blocklist), `GlobalExceptionHandler` +
`ApiError`, `ApiErrorWriter`, JWT issuance/filter, springdoc, `DevDataSeeder`
(`local` profile), collision-retry in `REQUIRES_NEW` transactions.
**Exit:** register → login → create → redirect returns 302 ✅; Swagger renders ✅;
1,000 creates / 50 threads produce zero duplicate codes ✅; **deployed URL ⏳**.
The code and local verification are complete; the only open P1 item is hosting.
**Touches:** `entity/*`, `repository/*`, `service/AuthService`, `service/LinkService`,
`service/LinkInserter`, `service/RedirectService`, `controller/*`, `security/*`,
`util/*`, `exception/*`, `config/properties/LinkforgeProperties`, `config/SecurityConfig`.

### Phase 2 — Auth completion
`refresh_tokens` with `family_id`, atomic rotation, reuse detection revoking the
family, logout, `USER`/`ADMIN`, method security. The existing `JwtService`,
`JwtAuthenticationFilter` and `AuthenticatedUser` are extended, not rewritten.
**Exit:** replaying an old refresh token kills the family; `USER` gets 403 on admin.

### Phase 3 — Link management
Custom aliases (reserved words, lowercase), expiry, enable/disable/soft delete,
paginated+sortable+searchable list, `Idempotency-Key` (Redis, 24 h), 404 vs 410.
**Exit:** FR-2..FR-5 and D3 acceptance cases pass; deleted code never reissued.

### Phase 4 — Redirect cache
Cache-aside (JSON, TTL bounded by expiry, ±10 % jitter), negative cache 30–60 s,
invalidation on update/disable/delete, `cache.hit`/`cache.miss` counters.
**Exit:** second redirect is a hit; Redis down → still 302 from DB; recovers.

### Phase 5 — Rate limiter
`RateLimitStrategy` + TokenBucket/SlidingWindow, `DefaultRedisScript` wiring,
`@RateLimited(limit, window, capacity, algorithm)`, `RateLimitInterceptor`,
`RateLimitKeyResolver`, `ClientIpResolver` (trusted proxies), 429 + headers,
fail-open redirect / in-memory fallback for auth.
**Exit:** two app instances against one Redis allow exactly `limit`; spoofed XFF
ignored from an untrusted peer; headers match script output.

### Phase 6 — Click pipeline + analytics
Bounded queue, batch writer (1 s / 500), drop counter, UA parser, HMAC visitor hash,
`PFADD`/`PFCOUNT`, nightly rollup (idempotent upsert), analytics endpoints with
UTC rollup/live stitching.
**Exit:** redirect p95 unaffected by a slowed write path; analytics totals equal
`count(*)` for a closed day.

### Phase 7 — Observability + k6
Micrometer timers/counters, `/actuator/prometheus`, Prometheus + Grafana in compose,
dashboard JSON, k6 scripts (redirect hit / miss / mixed 90-10), benchmarks.
**Exit:** dashboard shows RPS, p95, hit ratio, 429 rate; README §13 filled with
numbers **and the hardware**.

### Phase 8 — CI/CD, hardening, README
GitHub Actions (cache, Testcontainers job, image, GHCR, deploy), app service in
compose + Dockerfile, secret scanning, CORS, graceful shutdown, failure-mode tests.
**Exit:** green CI; `docker compose up` works on a clean clone; live URL + Grafana
link in README.

### Deferred (do not build)
API keys, Safe Browsing, GeoIP, `click_events` partitioning, Snowflake IDs, custom
domains, password-protected links, QR codes, CSV bulk upload, frontend.

---

## 6. Commands

```powershell
docker compose up -d              # postgres + redis
./mvnw spring-boot:run "-Dspring-boot.run.profiles=local"
./mvnw verify                     # unit + integration (needs Docker for Testcontainers)
./mvnw test                       # unit only (no Docker)
curl http://localhost:8080/actuator/health
```

Local DB: `postgresql://localhost:5432/linkforge`, user/pass `linkforge`/`linkforge`.
Override with `DB_URL`, `DB_USER`, `DB_PASSWORD`, `REDIS_HOST`, `REDIS_PORT`,
`JWT_SECRET`, `JWT_ISSUER`, `JWT_ACCESS_TTL`, `BASE_URL`, `PORT`.

---

## 7. Known constraints / risks

* **Boot 4 / Security 7**: lambda-only DSL; most tutorials are Boot 3. Keep security
  config minimal and compile early.
* **Boot 4 defaults to Jackson 3** (`tools.jackson.databind.ObjectMapper`). Jackson 2
  (`com.fasterxml.jackson.*`) is on the classpath transitively (springdoc, YAML) but
  Boot does **not** auto-configure a Jackson 2 `ObjectMapper` bean. Injecting the
  Jackson 2 type fails at startup with `NoSuchBeanDefinitionException`. Use
  `tools.jackson.databind.ObjectMapper` / `JsonNode` in new code. Jackson 3's
  `JsonNode.asText()` is deprecated in favour of `asString()`.
* **Boot 4 test annotations moved**: `@AutoConfigureMockMvc` is
  `org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc`, not the
  Boot 3 package.
* **JWT**: `spring-security-oauth2-jose` supplies `JwtEncoder`/`JwtDecoder` (Nimbus).
  HS256 requires ≥32 UTF-8 bytes; the configured default is a development fallback
  and must be replaced by `JWT_SECRET` before any deployment.
* **Boot 4 no longer imports the Testcontainers BOM** — it is pinned explicitly
  (`testcontainers.version = 2.0.5`). Testcontainers 2.x also renamed its artifacts
  (`testcontainers-junit-jupiter`, `testcontainers-postgresql`); the base class uses
  plain `GenericContainer` so it does not depend on those modules.
* **Free-tier hosting**: Redis/Postgres limits will distort cloud benchmarks. Benchmark
  locally in Compose and publish the hardware; report cloud numbers separately.
* **OneDrive**: `target/` inside OneDrive causes sync churn and file locks. Move the
  project to `C:\dev\linkforge` if builds get slow. During Phase 0 a large file move
  was partially undone by sync (`src/main/resources/scripts/` disappeared and was
  restored with `git checkout --`). After any move, run `git status` and confirm the
  tree before building.
* **Testcontainers** makes `./mvnw verify` require Docker. That is intentional.
* `docker compose` currently has no `app` service (added in P8); run the app from Maven.

---

## 8. Next action

1. **Deploy Phase 1.** Choose a host (the README leaves this TBD), provision Postgres 16
   and Redis 7 (or use managed services), and set at minimum `DB_URL`, `DB_USER`,
   `DB_PASSWORD`, `REDIS_HOST`, `REDIS_PORT`, `JWT_SECRET`, `BASE_URL` and `PORT`.
   Then re-run the register → login → create → 302 flow against the public URL and add
   it to the README. No code changes are expected.
2. **Then start Phase 2** (`refresh_tokens`, atomic rotation, family revocation on
   reuse, logout, roles, method security). Extend `JwtService`/`JwtAuthenticationFilter`
   rather than replacing them; the access-token contract is already in place.

