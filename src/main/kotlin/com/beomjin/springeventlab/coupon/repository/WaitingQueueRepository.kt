package com.beomjin.springeventlab.coupon.repository

import org.springframework.data.redis.core.StringRedisTemplate
import org.springframework.data.redis.core.script.RedisScript
import org.springframework.stereotype.Repository
import java.time.Duration
import java.util.UUID

@Repository
class WaitingQueueRepository(
    private val redisTemplate: StringRedisTemplate,
    private val enterQueueScript: RedisScript<Long>,
) {
    private fun waitingKey(eventId: UUID): String = "waiting:{$eventId}"

    private fun issuedKey(eventId: UUID): String = "coupon:issued:{$eventId}"

    private fun resultKey(
        eventId: UUID,
        userId: UUID,
    ): String = "result:{$eventId}:$userId"

    /**
     * Lua EVAL — 발급 여부 / 큐 중복 / 신규 ZADD를 1 RTT에 원자 처리.
     * 반환: SUCCESS / ALREADY_IN_QUEUE / ALREADY_ISSUED.
     */
    fun tryEnter(
        eventId: UUID,
        userId: UUID,
        scoreMs: Long,
        ttlSeconds: Long,
    ): EnterResult {
        val code =
            redisTemplate.execute(
                enterQueueScript,
                listOf(waitingKey(eventId), issuedKey(eventId)),
                userId.toString(),
                scoreMs.toString(),
                ttlSeconds.toString(),
            ) ?: throw IllegalStateException("Lua script returned null")
        return EnterResult.fromCode(code)
    }

    /** ZRANK 0-based → +1로 1-based 표기 (UI 친화). 큐에 없으면 null. */
    fun rank(
        eventId: UUID,
        userId: UUID,
    ): Long? =
        redisTemplate
            .opsForZSet()
            .rank(waitingKey(eventId), userId.toString())
            ?.let { it + 1 }

    fun size(eventId: UUID): Long = redisTemplate.opsForZSet().size(waitingKey(eventId)) ?: 0L

    /**
     * ZPOPMIN — score가 가장 작은(=먼저 진입한) 유저 N명을 원자적으로 제거하며 반환.
     * Spring Data Redis는 LinkedHashSet으로 score 오름차순 순서를 보존한다.
     */
    fun popMin(
        eventId: UUID,
        count: Long,
    ): List<UUID> {
        val popped = redisTemplate.opsForZSet().popMin(waitingKey(eventId), count) ?: return emptyList()
        return popped.mapNotNull { tuple -> tuple.value?.let(UUID::fromString) }
    }

    fun recordResult(
        eventId: UUID,
        userId: UUID,
        payload: String,
        ttl: Duration,
    ) {
        redisTemplate.opsForValue().set(resultKey(eventId, userId), payload, ttl)
    }

    fun findResult(
        eventId: UUID,
        userId: UUID,
    ): String? = redisTemplate.opsForValue().get(resultKey(eventId, userId))
}
