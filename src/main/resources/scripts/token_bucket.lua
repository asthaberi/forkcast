-- Token bucket rate limiter, executed atomically inside Redis.
--
-- Each key holds a bucket of up to `capacity` tokens that refills continuously at
-- `refill_tokens` per `refill_period_ms`. A request takes `cost` tokens if available.
-- Redis runs a script without interleaving other commands, so the read-refill-take-write
-- below can't be raced by concurrent requests, even from different app instances.
--
-- KEYS[1]  bucket key
-- ARGV[1]  capacity
-- ARGV[2]  refill_tokens
-- ARGV[3]  refill_period_ms
-- ARGV[4]  cost
-- Returns  {allowed (1/0), tokens left (floored), ms until enough tokens (0 if allowed)}

local capacity = tonumber(ARGV[1])
local refill_tokens = tonumber(ARGV[2])
local refill_period_ms = tonumber(ARGV[3])
local cost = tonumber(ARGV[4])

-- Redis's own clock, so app servers with skewed clocks still agree
local time = redis.call('TIME')
local now_ms = tonumber(time[1]) * 1000 + math.floor(tonumber(time[2]) / 1000)

local state = redis.call('HMGET', KEYS[1], 'tokens', 'updated_ms')
local tokens = tonumber(state[1])
local updated_ms = tonumber(state[2])
if tokens == nil then
  -- First request for this key: start with a full bucket
  tokens = capacity
  updated_ms = now_ms
end

local rate_per_ms = refill_tokens / refill_period_ms
tokens = math.min(capacity, tokens + math.max(0, now_ms - updated_ms) * rate_per_ms)

local allowed = 0
local wait_ms = 0
if tokens >= cost then
  tokens = tokens - cost
  allowed = 1
else
  wait_ms = math.ceil((cost - tokens) / rate_per_ms)
end

redis.call('HSET', KEYS[1], 'tokens', tostring(tokens), 'updated_ms', now_ms)
-- An idle bucket is full again after this long, so it can simply expire: memory stays bounded
redis.call('PEXPIRE', KEYS[1], math.ceil(capacity / rate_per_ms) + 1000)

return {allowed, math.floor(tokens), wait_ms}
