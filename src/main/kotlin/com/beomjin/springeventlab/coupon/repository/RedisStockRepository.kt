package com.beomjin.springeventlab.coupon.repository

import org.springframework.data.redis.core.StringRedisTemplate
import org.springframework.data.redis.core.script.RedisScript
import org.springframework.stereotype.Repository
import java.time.Duration
import java.util.UUID

@Repository
class RedisStockRepository(
    private val redisTemplate: StringRedisTemplate,
    private val issueCouponScript: RedisScript<Long>,
) {
    private fun stockKey(eventId: UUID): String = "coupon:stock:{$eventId}"

    private fun issuedKey(eventId: UUID): String = "coupon:issued:{$eventId}"

    /**
     * 재고를 Redis에 초기화한다 (이미 존재하면 무시).
     * stock 키: SET NX EX, issued 키: EXPIRE만 설정.
     */
    fun initStockIfAbsent(
        eventId: UUID,
        totalQuantity: Int,
        ttlSeconds: Long,
    ) {
        redisTemplate
            .opsForValue()
            .setIfAbsent(stockKey(eventId), totalQuantity.toString(), Duration.ofSeconds(ttlSeconds))
    }

    fun tryIssueCoupon(
        eventId: UUID,
        userId: UUID,
        ttlSeconds: Long,
    ): IssueResult {
        val code =
            redisTemplate.execute(
                issueCouponScript,
                listOf(stockKey(eventId), issuedKey(eventId)),
                userId.toString(),
                ttlSeconds.toString(),
            ) ?: throw IllegalStateException("Lua script returned null")
        return IssueResult.fromCode(code)
    }

    /**
     * DB 저장 실패 시 Redis 보상: 발급 기록 제거 + 재고 복원.
     */
    fun compensate(
        eventId: UUID,
        userId: UUID,
    ) {
        redisTemplate.opsForSet().remove(issuedKey(eventId), userId.toString())
        redisTemplate.opsForValue().increment(stockKey(eventId))
    }

    /**
     * 신규 restoreStock: INCR만 (UK 위반용 — issued Set 유지)
     */
    fun restoreStock(eventId: UUID) {
        redisTemplate.opsForValue().increment(stockKey(eventId))
    }
}
