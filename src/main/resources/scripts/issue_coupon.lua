-- issue_coupon.lua
-- KEYS[1] = coupon:stock:{eventId}
-- KEYS[2] = coupon:issued:{eventId}
-- ARGV[1] = userId

-- 1) 재고 확인 — 매진이 전체 트래픽의 99%+ → 읽기 1회로 즉시 탈출
local stock = tonumber(redis.call('GET', KEYS[1]))
if stock == nil or stock <= 0 then
    return 0   -- 매진
end

-- 2) 중복 확인 — 읽기 1회 추가
if redis.call('SISMEMBER', KEYS[2], ARGV[1]) == 1 then
    return -1  -- 이미 발급됨
end

-- 3) 성공 경로에서만 쓰기 발생
redis.call('DECR', KEYS[1])
redis.call('SADD', KEYS[2], ARGV[1])
return 1       -- 발급 성공
