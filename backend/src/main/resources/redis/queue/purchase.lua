-- KEYS: active ZSET, registered-at HASH, claim STRING (per user/concert).
-- ARGV: operation, userId, admissionId, owner, exp ms, now ms.
-- Validate all key types before mutations: Redis script errors do not roll back.
local types = {'zset', 'hash', 'string'}
for i = 1, 3 do
    local actual = redis.call('TYPE', KEYS[i]).ok
    if actual ~= 'none' and actual ~= types[i] then error('QUEUE invalid purchase key type') end
end
local operation, user, admission, owner = ARGV[1], ARGV[2], ARGV[3], ARGV[4]
if operation ~= 'claim' and operation ~= 'consume' and operation ~= 'release' then
    error('QUEUE invalid purchase operation')
end
local currentAdmission = redis.call('HGET', KEYS[2], user .. ':admission')
local score = redis.call('ZSCORE', KEYS[1], user)
if operation == 'claim' then
    local expiry, now = tonumber(ARGV[5]), tonumber(ARGV[6])
    if not expiry or not now then error('QUEUE invalid purchase timestamp') end
    if expiry <= now or not score or tonumber(score) <= now or currentAdmission ~= admission
        or math.floor(tonumber(score) / 1000) * 1000 ~= expiry then return 0 end
    if redis.call('EXISTS', KEYS[3]) == 1 then return 2 end
    redis.call('SET', KEYS[3], admission .. ':' .. owner, 'PX', expiry - now)
    return 1
end
if operation == 'release' and redis.call('GET', KEYS[3]) ~= admission .. ':' .. owner then return 0 end
if operation == 'consume' and currentAdmission ~= admission then return 0 end
if operation == 'consume' then
    redis.call('ZREM', KEYS[1], user)
    redis.call('HDEL', KEYS[2], user, user .. ':admission')
end
redis.call('DEL', KEYS[3])
return 1
