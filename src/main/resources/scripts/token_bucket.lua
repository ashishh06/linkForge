-- Token bucket rate limiter (atomic).
-- Location: src/main/resources/scripts/token_bucket.lua
--
-- KEYS[1] = bucket key, e.g. "rl:v1:tb:create:user:42"
--           The "v1" segment means a change to capacity/refill semantics can be
--           rolled out by bumping the version instead of reusing stale buckets.
-- ARGV[1] = capacity (max burst size)
-- ARGV[2] = refill rate in tokens per second (e.g. 20 per minute => 0.3333)
-- ARGV[3] = tokens requested (normally 1)
--
-- Returns { allowed (1/0), remaining_tokens, retry_after_ms, reset_ms }
--   retry_after_ms: how long until the request would succeed (0 if allowed)
--   reset_ms:       how long until the bucket is full again
--
-- Both durations are MILLISECONDS. The Java layer converts them:
--   Retry-After        = ceil(retry_after_ms / 1000) seconds
--   X-RateLimit-Reset  = epoch seconds (now + reset_ms)
--
-- Redis 7 replicates the effects of non-deterministic commands, so TIME is safe
-- here. On Redis < 5 this script would need redis.replicate_commands() first.

local key            = KEYS[1]
local capacity       = tonumber(ARGV[1])
local refill_per_sec = tonumber(ARGV[2])
local requested      = tonumber(ARGV[3])

-- Fail loudly on a bad configuration. Without this, refill_per_sec = 0 produces
-- inf and Redis rejects the final table with "value is not an integer or out of
-- range" -- a 500 on the hottest path instead of a clear error.
if capacity == nil or capacity <= 0
   or refill_per_sec == nil or refill_per_sec <= 0
   or requested == nil or requested <= 0
   or requested > capacity then
  return redis.error_reply(
    'ERR token_bucket: invalid arguments (need capacity>0, refill>0, 0<requested<=capacity)')
end

-- Use the Redis server clock so multiple app instances never disagree about time.
local t      = redis.call('TIME')
local now_ms = tonumber(t[1]) * 1000 + math.floor(tonumber(t[2]) / 1000)

-- HMGET returns false (not nil) for a missing field.
local data   = redis.call('HMGET', key, 'tokens', 'ts')
local tokens = tonumber(data[1])
local ts     = tonumber(data[2])

if tokens == nil or ts == nil then
  tokens = capacity
  ts     = now_ms
end

-- Guard against a capacity reduction between deploys.
if tokens > capacity then
  tokens = capacity
end

-- Refill based on elapsed time, capped at capacity.
local elapsed_ms = math.max(0, now_ms - ts)
tokens = math.min(capacity, tokens + (elapsed_ms / 1000) * refill_per_sec)

local allowed        = 0
local retry_after_ms = 0

-- 1e-9 absorbs float error: refill is fractional, so a bucket that should hold
-- exactly `requested` tokens can land on 4.999999999 and wrongly deny.
if tokens + 1e-9 >= requested then
  tokens  = tokens - requested
  allowed = 1
else
  retry_after_ms = math.ceil(((requested - tokens) / refill_per_sec) * 1000)
end

redis.call('HSET', key, 'tokens', tostring(tokens), 'ts', now_ms)
-- Expire idle buckets: after a full refill the state is equivalent to "no key".
redis.call('EXPIRE', key, math.ceil(capacity / refill_per_sec) + 1)

local reset_ms = math.ceil(((capacity - tokens) / refill_per_sec) * 1000)

return { allowed, math.floor(tokens), retry_after_ms, reset_ms }
