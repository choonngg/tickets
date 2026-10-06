if ARGV[2] == '' then fail('status requires user ID') end
local user = ARGV[2]
-- WAITING is the common query path. Expired data stays for bounded cleanup.
local waiting = redis.call('ZSCORE', KEYS[2], user)
if waiting then
    if tonumber(waiting) <= now then return result('EXPIRED') end
    return waitingResult(user)
end
local active = redis.call('ZSCORE', KEYS[4], user)
if active then
    if tonumber(active) <= now then return result('EXPIRED') end
    return result('ACTIVE', tonumber(active))
end
local admitted = redis.call('ZSCORE', KEYS[3], user)
if admitted then
    if tonumber(admitted) <= now then return result('EXPIRED') end
    return result('ADMITTED', tonumber(admitted))
end
return result('EXPIRED')
