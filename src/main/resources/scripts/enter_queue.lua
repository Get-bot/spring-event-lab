-- enter_queue.lua
-- KEYS[1] = waiting:{eventId}
-- KEYS[2] = coupon:issued:{eventId}
-- ARGV[1] = userId
-- ARGV[2] = score (arrival timestamp, ms)
-- ARGV[3] = ttlSeconds

-- 1) 이미 발급된 유저인지 — race condition으로 enter와 issue가 교차해도 차단
if redis.call('SISMEMBER', KEYS[2], ARGV[1]) == 1 then
    return -1
end

-- 2) 이미 대기열에 있는지 — 중복 enter 차단
if redis.call('ZSCORE', KEYS[1], ARGV[1]) ~= false then
    return 0
end

-- 3) ZADD + 최초 진입 시 TTL 설정
redis.call('ZADD', KEYS[1], ARGV[2], ARGV[1])
if redis.call('TTL', KEYS[1]) < 0 then
    redis.call('EXPIRE', KEYS[1], ARGV[3])
end

return 1
