if ARGV[2] ~= '' then fail('admission cycle takes no user ID') end
local expired = prepareCleanup()
-- Cleanup may remove at most cleanupLimit from waiting; validate all possible candidates
-- before cleanup writes, without scanning the whole queue.
local candidates = redis.call('ZRANGE', KEYS[1], 0, cycleLimit + cleanupLimit - 1)
for _, user in ipairs(candidates) do validateMember(user) end
local cleaned = cleanup(expired)
local capacity = math.max(0, maximum - redis.call('ZCARD', KEYS[3]) - redis.call('ZCARD', KEYS[4]))
local count = math.min(redis.call('ZCARD', KEYS[1]), capacity, cycleLimit)
local admittedCount = 0
if count > 0 then
    local popped = redis.call('ZPOPMIN', KEYS[1], count)
    for i = 1, #popped, 2 do
        local user = popped[i]
        local expiry = redis.call('ZSCORE', KEYS[2], user)
        if expiry and tonumber(expiry) > now then
            redis.call('ZREM', KEYS[2], user)
            redis.call('ZADD', KEYS[3], now + admittedTtl, user)
            admittedCount = admittedCount + 1
        end
        -- Expired popped users retain waiting-expiry/registered-at for subsequent cleanup.
        -- Do not replace these candidates: the next worker cycle handles the shortfall.
    end
end
removeEmptyPerformance()
redis.call('LPUSH', KEYS[7], admittedCount)
redis.call('LTRIM', KEYS[7], 0, 11)
local sum = 0
for _, value in ipairs(redis.call('LRANGE', KEYS[7], 0, 11)) do sum = sum + tonumber(value) end
redis.call('SET', KEYS[8], sum)
return {admittedCount, cleaned}
