-- KEYS: waiting, waiting-expiry, admitted, active, registered-at, sequence,
--       admit-history, admit-rate, queue:active-performances (single Redis only).
-- ARGV: performanceId, userId (empty for admit), now epoch ms, max, cycle limit,
--       admitted ttl ms, active ttl ms, cleanup limit, grace ms,
--       fast rank, medium rank, fast poll ms, medium poll ms, slow poll ms, registration UUID.
-- Redis Lua errors do not roll back writes: all validation must precede mutations.
local safe = 9007199254740991
local function fail(message) error('QUEUE ' .. message) end
local function integer(value, minimum)
    if not value or not string.match(value, '^%d+$') then fail('invalid integer') end
    local n = tonumber(value)
    if not n or n < minimum or n > safe or n ~= math.floor(n) then fail('invalid integer range') end
    return n
end
local function id(value)
    -- IDs are never converted to doubles (Java long IDs may exceed 2^53).
    if not value or not string.match(value, '^[1-9]%d*$') or #value > 19
        or (#value == 19 and value > '9223372036854775807') then fail('invalid ID') end
end
if #KEYS ~= 9 or #ARGV ~= 15 then fail('invalid key/argument count') end
if not string.match(ARGV[15], '^[0-9a-f]+%-[0-9a-f]+%-[0-9a-f]+%-[0-9a-f]+%-[0-9a-f]+$')
    or #ARGV[15] ~= 36 then fail('invalid admission ID') end
id(ARGV[1])
if ARGV[2] ~= '' then id(ARGV[2]) end
local expectedTypes = {'zset', 'zset', 'zset', 'zset', 'hash', 'string', 'list', 'string', 'set'}
local suffixes = {'waiting', 'waiting-expiry', 'admitted', 'active', 'registered-at', 'sequence', 'admit-history', 'admit-rate'}
for i = 1, 9 do
    local expectedKey = i == 9 and 'queue:active-performances' or ('queue:' .. ARGV[1] .. ':' .. suffixes[i])
    if KEYS[i] ~= expectedKey then fail('invalid key name') end
    local actual = redis.call('TYPE', KEYS[i]).ok
    if actual ~= 'none' and actual ~= expectedTypes[i] then fail('invalid key type: ' .. KEYS[i]) end
end
local now = integer(ARGV[3], 0)
local maximum = integer(ARGV[4], 1)
local cycleLimit = integer(ARGV[5], 1)
local admittedTtl = integer(ARGV[6], 1)
local activeTtl = integer(ARGV[7], 1)
local cleanupLimit = integer(ARGV[8], 1)
local grace = integer(ARGV[9], 1)
local fastRank = integer(ARGV[10], 1)
local mediumRank = integer(ARGV[11], 1)
local fastPoll = integer(ARGV[12], 1)
local mediumPoll = integer(ARGV[13], 1)
local slowPoll = integer(ARGV[14], 1)
if cleanupLimit > 200 or fastRank >= mediumRank or fastPoll > mediumPoll or mediumPoll > slowPoll
    or now + math.max(admittedTtl, activeTtl, grace, slowPoll * 3) > safe then fail('invalid settings') end
local sequence = redis.call('GET', KEYS[6])
if sequence then
    integer(sequence, 0)
    -- INCR rejects noncanonical integers such as "01" even though tonumber accepts them.
    if sequence ~= '0' and not string.match(sequence, '^[1-9]%d*$') then fail('invalid sequence') end
end
local rate = redis.call('GET', KEYS[8])
if rate then integer(rate, 0) end
if redis.call('LLEN', KEYS[7]) > 12 then fail('invalid history length') end
local history = redis.call('LRANGE', KEYS[7], 0, 11)
local historySum = 0
for _, count in ipairs(history) do historySum = historySum + integer(count, 0) end
if historySum + cycleLimit > safe then fail('history overflow') end

local function validateMember(user)
    local waiting = redis.call('ZSCORE', KEYS[1], user)
    local expiry = redis.call('ZSCORE', KEYS[2], user)
    local admitted = redis.call('ZSCORE', KEYS[3], user)
    local active = redis.call('ZSCORE', KEYS[4], user)
    for _, score in pairs({waiting, expiry, admitted, active}) do
        if score then integer(score, 0) end
    end
    local states = (expiry and 1 or 0) + (admitted and 1 or 0) + (active and 1 or 0)
    if states > 1 or (waiting and not expiry) or (waiting and (admitted or active)) then fail('conflicting member state') end
    local registered = redis.call('HGET', KEYS[5], user)
    if registered then integer(registered, 0) end
    if waiting and not registered then fail('missing registration time') end
end
if ARGV[2] ~= '' then validateMember(ARGV[2]) end
local function poll(rank)
    if rank <= fastRank then return fastPoll end
    if rank <= mediumRank then return mediumPoll end
    return slowPoll
end
local function result(state, expiry)
    return {state, 0, 0, expiry or 0, 0, rate and tonumber(rate) or 0}
end
local function waitingResult(user)
    local rank = redis.call('ZRANK', KEYS[1], user)
    if not rank then return result('EXPIRED') end
    rank = rank + 1
    local interval = poll(rank)
    local expiry = now + interval * 3
    redis.call('ZADD', KEYS[2], expiry, user)
    return {'WAITING', rank, redis.call('ZCARD', KEYS[1]) - rank, expiry, interval, rate and tonumber(rate) or 0}
end
local function removeMember(user)
    for i = 1, 4 do redis.call('ZREM', KEYS[i], user) end
    redis.call('HDEL', KEYS[5], user, user .. ':admission')
end
local function removeEmptyPerformance()
    if redis.call('ZCARD', KEYS[1]) == 0 and redis.call('ZCARD', KEYS[2]) == 0
        and redis.call('ZCARD', KEYS[3]) == 0 and redis.call('ZCARD', KEYS[4]) == 0
        and redis.call('HLEN', KEYS[5]) == 0 then
        redis.call('SREM', KEYS[9], ARGV[1])
    end
end
-- Prepare and validate the entire bounded batch before its first write.
local function prepareCleanup()
    local users = {}
    for i = 2, 4 do
        local remaining = cleanupLimit - #users
        if remaining > 0 then
            local expired = redis.call('ZRANGEBYSCORE', KEYS[i], '-inf', now, 'LIMIT', 0, remaining)
            for _, user in ipairs(expired) do
                validateMember(user)
                table.insert(users, user)
            end
        end
    end
    return users
end
local function cleanup(users)
    for _, user in ipairs(users) do removeMember(user) end
    return #users
end
