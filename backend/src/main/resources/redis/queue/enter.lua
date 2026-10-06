if ARGV[2] == '' then fail('entry requires user ID') end
local user = ARGV[2]
local active = redis.call('ZSCORE', KEYS[4], user)
local admitted = redis.call('ZSCORE', KEYS[3], user)
local expired = prepareCleanup()
cleanup(expired)
local response
if active then
    if tonumber(active) > now then response = result('ACTIVE', tonumber(active))
    else response = result('EXPIRED') end
elseif admitted then
    if tonumber(admitted) > now then
        redis.call('ZREM', KEYS[3], user)
        redis.call('ZADD', KEYS[4], now + activeTtl, user)
        response = result('ACTIVE', now + activeTtl)
    else response = result('EXPIRED') end
else
    response = result('NOT_ADMITTED')
end
removeEmptyPerformance()
return response
