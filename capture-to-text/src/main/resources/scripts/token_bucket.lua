-- Token bucket rate limiter. One bucket per key (user + route group).

-- KEYS[1] = bucket key; ARGV[1] = capacity (max burst); ARGV[2] = tokens refilled per millisecond.
-- Returns {allowed (1/0), milliseconds until the next token}.

-- Redis runs a script atomically: it doesnt interleave another client's attempt to read/write to the script at the same time, so two API Pods handling the same user at the same instant can't both spend the last
-- token.
-- The clock is Redis's own (TIME), so Pods with slightly different clocks still agree.

-- Lua arrays is not zero indexed, i.e. starts from 1, and everything arrives in string, i.e. the tonumber()
local capacity = tonumber(ARGV[1])
local rate = tonumber(ARGV[2])
local time = redis.call('TIME')

-- this turn the cur ts into millis
local now = time[1] * 1000 + math.floor(time[2] / 1000)

-- a hash/dic is created under the key that is identifier for a user's bucket, and tokens and ts are the fields in said dictionary
-- bucket is an array of dics under the key, with tokens and ts as fields? bucket[1] is the tokens field, [2] for ts, 
local bucket = redis.call('HMGET', KEYS[1], 'tokens', 'ts')
local tokens = tonumber(bucket[1]) or capacity -- a new bucket starts full
local last = tonumber(bucket[2]) or now

-- Refill for the time since the last request, never above capacity.
tokens = math.min(capacity, tokens + math.max(0, now - last) * rate)

local allowed, retryAfterMs = 0, 0
if tokens >= 1 then
  tokens = tokens - 1
  allowed = 1
else

  -- atleast wait for an interval for '1' token to get refreshed
  retryAfterMs = math.ceil((1 - tokens) / rate)
end

-- saves the bucket array 
redis.call('HSET', KEYS[1], 'tokens', tokens, 'ts', now)
-- An idle bucket would be full again by then anyway, so let Redis delete it.
redis.call('PEXPIRE', KEYS[1], math.ceil(capacity / rate))
return {allowed, retryAfterMs}
