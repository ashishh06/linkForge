-- Sliding window COUNTER rate limiter (atomic).
-- Location: src/main/resources/scripts/sliding_window.lua
--
-- Approximates a true sliding window using two fixed windows:
--   estimated = previous_window_count * (1 - fraction_of_current_window_elapsed) + current_window_count
--
-- KEYS[1] = key prefix, e.g. "rl:sw:{login:ip:203.0.113.5}"
-- ARGV[1] = limit (max requests per window)
-- ARGV[2] = window size in milliseconds (e.g. 60000)
--
-- Returns { allowed (1/0), remaining, retry_after_ms, reset_ms }
--   retry_after_ms: ms until one more request would be allowed (0 if allowed)
--   reset_ms:       ms until the current window rolls over
--
-- Both durations are MILLISECONDS. The Java layer converts them:
--   Retry-After        = ceil(retry_after_ms / 1000) seconds
--   X-RateLimit-Reset  = epoch seconds (now + reset_ms)
--
-- The script builds the per-window keys from KEYS[1] itself because it needs the
-- server time to know which window it is in. On Redis Cluster, ALWAYS pass the
-- identity wrapped in a hash tag -- "rl:sw:{user:42}" -- so every derived key
-- lands in the same slot. The interceptor applies the braces; the script assumes
-- KEYS[1] is the raw identity prefix without them.
--
-- Redis 7 replicates the effects of non-deterministic commands, so TIME is safe
-- here. On Redis < 5 this script would need redis.replicate_commands() first.

local prefix    = KEYS[1]
local limit_n   = tonumber(ARGV[1])
local window_ms = tonumber(ARGV[2])

if limit_n == nil or limit_n <= 0 or window_ms == nil or window_ms <= 0 then
  return redis.error_reply(
    'ERR sliding_window: invalid arguments (need limit>0, window_ms>0)')
end

-- Milliseconds until `estimated + 1 <= limit_n` can hold again.
--
-- While the current window is open, only the previous window's weight decays
-- (the current count is not weighted). At the rollover the current count becomes
-- the previous count and starts decaying too. Reporting the plain rollover time
-- is wrong in both directions: too pessimistic when the previous window dominates,
-- too optimistic when the current window is already full (the client retries and
-- gets another 429, i.e. a retry storm).
local function wait_until_allowed(prev, curr, limit_n, window_ms, elapsed)
  local needed = prev + curr + 1 - limit_n
  if needed <= 0 then
    return 0
  end

  local left_in_window = window_ms - elapsed

  -- (a) A solution may exist before the window rolls over.
  if prev > 0 then
    local d = (needed * window_ms / prev) - elapsed
    if d <= left_in_window then
      return math.max(0, math.ceil(d))
    end
  end

  -- (b) Otherwise wait for the rollover, then solve with prev' = curr, curr' = 0.
  if curr + 1 <= limit_n then
    return math.ceil(left_in_window)
  end
  local d2 = (curr + 1 - limit_n) * window_ms / curr
  return math.ceil(left_in_window + math.max(0, d2))
end

local t      = redis.call('TIME')
local now_ms = tonumber(t[1]) * 1000 + math.floor(tonumber(t[2]) / 1000)

local current_window = math.floor(now_ms / window_ms)
local elapsed_in_win = now_ms - (current_window * window_ms)
local fraction       = elapsed_in_win / window_ms

local curr_key = prefix .. ':' .. current_window
local prev_key = prefix .. ':' .. (current_window - 1)

-- GET returns false for a missing key; `or '0'` normalises it.
local curr = tonumber(redis.call('GET', curr_key) or '0')
local prev = tonumber(redis.call('GET', prev_key) or '0')

local estimated = prev * (1 - fraction) + curr

local allowed        = 0
local retry_after_ms = 0

-- 1e-9 absorbs float error: the weighted estimate is fractional, so at the exact
-- moment a request should fit it can land on 100.00000000000001 and be denied.
if estimated + 1 <= limit_n + 1e-9 then
  curr = redis.call('INCR', curr_key)
  -- Keep the key for two windows so it can serve as "previous" next window.
  redis.call('PEXPIRE', curr_key, window_ms * 2)
  estimated = estimated + 1
  allowed   = 1
else
  retry_after_ms = wait_until_allowed(prev, curr, limit_n, window_ms, elapsed_in_win)
end

local remaining = math.max(0, math.floor(limit_n - estimated))
local reset_ms  = window_ms - elapsed_in_win

return { allowed, remaining, retry_after_ms, reset_ms }
