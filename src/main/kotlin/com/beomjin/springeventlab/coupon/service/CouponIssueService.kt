package com.beomjin.springeventlab.coupon.service

import com.beomjin.springeventlab.coupon.dto.message.CouponIssueMessage
import com.beomjin.springeventlab.coupon.dto.response.CouponIssueResponse
import com.beomjin.springeventlab.coupon.producer.CouponIssueProducer
import com.beomjin.springeventlab.coupon.repository.EventRepository
import com.beomjin.springeventlab.coupon.repository.IssueResult
import com.beomjin.springeventlab.coupon.repository.RedisStockRepository
import com.beomjin.springeventlab.global.exception.BusinessException
import com.beomjin.springeventlab.global.exception.ErrorCode
import com.github.f4b6a3.uuid.UuidCreator
import org.springframework.data.repository.findByIdOrNull
import org.springframework.stereotype.Service
import java.time.Duration
import java.time.Instant
import java.util.UUID

@Service
class CouponIssueService(
    private val eventRepository: EventRepository,
    private val redisStockRepository: RedisStockRepository,
    private val couponIssueProducer: CouponIssueProducer,
) {
    fun issue(
        eventId: UUID,
        userId: UUID,
    ): CouponIssueResponse {
        // 1. 이벤트 조회 및 검증
        val event =
            eventRepository.findByIdOrNull(eventId)
                ?: throw BusinessException(ErrorCode.EVENT_NOT_FOUND)

        val now = Instant.now()
        if (!event.period.contains(now)) {
            throw BusinessException(ErrorCode.EVENT_NOT_OPEN)
        }

        // 2. Redis 재고 Lazy Init
        val ttlSeconds =
            Duration
                .between(now, event.period.endedAt)
                .plusHours(1)
                .toSeconds()
        redisStockRepository.initStockIfAbsent(eventId, event.totalQuantity, ttlSeconds)

        // 3. Redis에서 발급 시도
        when (redisStockRepository.tryIssueCoupon(eventId, userId, ttlSeconds)) {
            IssueResult.ALREADY_ISSUED -> throw BusinessException(ErrorCode.COUPON_ALREADY_ISSUED)
            IssueResult.SOLD_OUT -> throw BusinessException(ErrorCode.EVENT_SOLD_OUT)
            IssueResult.SUCCESS -> Unit
        }

        // 4. Kafka 발행 — id/issuedAt을 Producer에서 사전 확정하여 응답과 메시지에 동일 값 사용
        val issueId = UuidCreator.getTimeOrderedEpoch()
        couponIssueProducer.publish(
            CouponIssueMessage(id = issueId, eventId = eventId, userId = userId, issuedAt = now),
        )

        return CouponIssueResponse(
            id = issueId,
            eventId = eventId,
            userId = userId,
            createdAt = now,
        )
    }
}
