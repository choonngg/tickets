if ARGV[2] == '' then fail('registration requires user ID') end
local user = ARGV[2]
local active = redis.call('ZSCORE', KEYS[4], user)
if active and tonumber(active) > now then return result('ACTIVE', tonumber(active)) end
local admitted = redis.call('ZSCORE', KEYS[3], user)
if admitted and tonumber(admitted) > now then return result('ADMITTED', tonumber(admitted)) end
local registered = redis.call('HGET', KEYS[5], user)
local waitingExpiry = redis.call('ZSCORE', KEYS[2], user)
if redis.call('ZSCORE', KEYS[1], user) and waitingExpiry and tonumber(waitingExpiry) > now
    and registered and now - tonumber(registered) <= grace then
    -- An unexpired duplicate registration is a liveness signal, without resetting registered-at.
    local current = waitingResult(user)
    redis.call('SADD', KEYS[9], ARGV[1])
    return current
end
if sequence and tonumber(sequence) >= safe then fail('sequence exhausted') end
-- Expired WAITING/ACTIVE/ADMITTED may re-register before the next cleanup batch.
removeMember(user)
local nextSequence = redis.call('INCR', KEYS[6])
redis.call('ZADD', KEYS[1], nextSequence, user)
redis.call('HSET', KEYS[5], user, ARGV[3])
redis.call('SADD', KEYS[9], ARGV[1])
-- Calculate the polling interval from the newly inserted 1-based rank.
return waitingResult(user)
