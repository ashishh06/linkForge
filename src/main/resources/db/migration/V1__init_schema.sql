-- LinkForge initial schema (v2 scope)
-- Flyway location: src/main/resources/db/migration/V1__init_schema.sql
--
-- Conventions
--   * Every timestamp is TIMESTAMPTZ and is written/read in UTC.
--   * Analytics day buckets (link_stats_daily.stat_date) are UTC days. The rollup
--     job, the analytics endpoints and the HLL keys must all use UTC, or the daily
--     series and the totals will disagree.
--   * Emails are stored lowercase (enforced below); the application normalises the
--     address before insert so lookups by email can use the plain unique index.
--   * Short codes are CASE-SENSITIVE (Base62 requires it). Custom aliases are
--     stored lowercase, so /MyLink and /mylink cannot both exist.

CREATE TABLE users (
    id            BIGSERIAL    PRIMARY KEY,
    email         VARCHAR(255) NOT NULL,
    password_hash VARCHAR(100) NOT NULL,
    role          VARCHAR(20)  NOT NULL DEFAULT 'USER',
    created_at    TIMESTAMPTZ  NOT NULL DEFAULT now(),
    CONSTRAINT uq_users_email UNIQUE (email),
    CONSTRAINT chk_users_email_lowercase CHECK (email = lower(email)),
    CONSTRAINT chk_users_role CHECK (role IN ('USER', 'ADMIN'))
);

-- Refresh tokens with family-based reuse detection (FR-17).
-- Rotation MUST be atomic to be correct:
--   UPDATE refresh_tokens SET used_at = now()
--    WHERE token_hash = $1 AND used_at IS NULL AND revoked = FALSE
--   RETURNING id, family_id;
-- A read-then-write allows two concurrent refreshes with the same token to both
-- succeed and fork the family.
CREATE TABLE refresh_tokens (
    id          BIGSERIAL   PRIMARY KEY,
    user_id     BIGINT      NOT NULL REFERENCES users (id) ON DELETE CASCADE,
    family_id   UUID        NOT NULL,
    token_hash  VARCHAR(64) NOT NULL,   -- SHA-256 hex of the token; never store the raw token
    expires_at  TIMESTAMPTZ NOT NULL,
    used_at     TIMESTAMPTZ,            -- set when rotated; presenting a used token again => revoke family
    revoked     BOOLEAN     NOT NULL DEFAULT FALSE,
    created_at  TIMESTAMPTZ NOT NULL DEFAULT now(),
    CONSTRAINT uq_refresh_tokens_hash UNIQUE (token_hash),
    CONSTRAINT chk_refresh_tokens_expiry CHECK (expires_at > created_at)
);
CREATE INDEX idx_refresh_tokens_family ON refresh_tokens (family_id);
CREATE INDEX idx_refresh_tokens_user   ON refresh_tokens (user_id);
-- Cheap "revoke everything for this user" on logout-all / family revocation.
CREATE INDEX idx_refresh_tokens_active ON refresh_tokens (user_id) WHERE revoked = FALSE;

-- Links (D1, D2, D3).
-- short_code is unique across ALL rows including soft-deleted ones, so a code is
-- never reissued. Deliberately NO unique index on (user_id, original_url): each
-- create makes a new link (D2), and duplicate suppression is done with the
-- Idempotency-Key header instead.
CREATE TABLE links (
    id              BIGSERIAL     PRIMARY KEY,
    short_code      VARCHAR(20)   NOT NULL,
    original_url    VARCHAR(2048) NOT NULL,
    user_id         BIGINT        NOT NULL REFERENCES users (id) ON DELETE RESTRICT,
    is_custom_alias BOOLEAN       NOT NULL DEFAULT FALSE,
    expires_at      TIMESTAMPTZ,
    is_active       BOOLEAN       NOT NULL DEFAULT TRUE,
    deleted_at      TIMESTAMPTZ,
    created_at      TIMESTAMPTZ   NOT NULL DEFAULT now(),
    updated_at      TIMESTAMPTZ   NOT NULL DEFAULT now(),
    CONSTRAINT uq_links_short_code UNIQUE (short_code),
    -- Generated Base62 codes and custom aliases share one charset.
    CONSTRAINT chk_links_short_code_charset CHECK (short_code ~ '^[A-Za-z0-9-]{3,20}$'),
    CONSTRAINT chk_links_alias_lowercase CHECK (NOT is_custom_alias OR short_code = lower(short_code)),
    -- Cheap sanity check only; full validation lives in the application (D5).
    CONSTRAINT chk_links_url_scheme CHECK (original_url ~* '^https?://'),
    CONSTRAINT chk_links_expiry CHECK (expires_at IS NULL OR expires_at > created_at)
);
CREATE INDEX idx_links_user_created ON links (user_id, created_at DESC);

-- Raw click events (FR-13). visitor_hash = HMAC(daily secret, ip + user agent);
-- the raw IP is never stored. This table is the authoritative source for clicks.
-- referrer is truncated by the application before insert (max 512 chars).
CREATE TABLE click_events (
    id           BIGSERIAL   PRIMARY KEY,
    link_id      BIGINT      NOT NULL REFERENCES links (id) ON DELETE RESTRICT,
    clicked_at   TIMESTAMPTZ NOT NULL,
    referrer     VARCHAR(512),
    browser      VARCHAR(50),
    os           VARCHAR(50),
    device_type  VARCHAR(20),
    visitor_hash VARCHAR(64)
);
-- Per-link analytics over a range.
CREATE INDEX idx_click_events_link_time ON click_events (link_id, clicked_at);
-- The nightly rollup scans a TIME range across ALL links; (link_id, clicked_at)
-- cannot serve that. Keep this btree for predictable plans; if the table grows
-- into the tens of millions of rows, replace it with a BRIN on clicked_at.
CREATE INDEX idx_click_events_clicked_at ON click_events (clicked_at);

-- Daily rollups written by the nightly aggregation job (FR-15). This is a
-- materialised cache, not a second source of truth: click_events stays
-- authoritative, and the analytics API stitches "today" from click_events onto
-- the completed days from this table at the UTC day boundary.
--
-- unique_visitors is approximate (Redis HLL) and must be written ONCE per day:
-- the HLL keys expire after 90 days, so a late re-run cannot recompute it.
-- The rollup write must be idempotent:
--   INSERT ... ON CONFLICT (link_id, stat_date) DO UPDATE SET ...
CREATE TABLE link_stats_daily (
    link_id         BIGINT      NOT NULL REFERENCES links (id) ON DELETE RESTRICT,
    stat_date       DATE        NOT NULL,               -- UTC day
    total_clicks    BIGINT      NOT NULL DEFAULT 0,
    unique_visitors BIGINT      NOT NULL DEFAULT 0,     -- approximate, from Redis HLL (D4)
    computed_at     TIMESTAMPTZ NOT NULL DEFAULT now(),
    PRIMARY KEY (link_id, stat_date)                     -- also serves range queries
);

-- Matching is suffix-based in the application: blocking "evil.com" also blocks
-- "a.evil.com". Store the lowercase apex or subdomain.
CREATE TABLE blocked_domains (
    id         BIGSERIAL    PRIMARY KEY,
    domain     VARCHAR(255) NOT NULL,
    reason     VARCHAR(255),
    created_at TIMESTAMPTZ  NOT NULL DEFAULT now(),
    CONSTRAINT uq_blocked_domains_domain UNIQUE (domain),
    CONSTRAINT chk_blocked_domains_lowercase CHECK (domain = lower(domain))
);
