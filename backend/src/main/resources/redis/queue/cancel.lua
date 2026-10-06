if ARGV[2] == '' then fail('cancellation requires user ID') end
-- A stateless Entry Token remains valid until this absolute expiry; never free its slot early.
local active = redis.call('ZSCORE', KEYS[4], ARGV[2])
if active and tonumber(active) > now then return result('ACTIVE', tonumber(active)) end
-- Shared validation finishes before writes; other states/missing users cancel idempotently.
removeMember(ARGV[2])
removeEmptyPerformance()
return result('EXPIRED')
