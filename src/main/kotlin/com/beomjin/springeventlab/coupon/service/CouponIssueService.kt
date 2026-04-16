package com.beomjin.springeventlab.coupon.service

import com.beomjin.springeventlab.coupon.dto.response.CouponIssueResponse
import com.beomjin.springeventlab.coupon.repository.EventRepository
import com.beomjin.springeventlab.coupon.repository.IssueResult
import com.beomjin.springeventlab.coupon.repository.RedisStockRepository
import com.beomjin.springeventlab.global.exception.BusinessException
import com.beomjin.springeventlab.global.exception.ErrorCode
import io.github.oshai.kotlinlogging.KotlinLogging
import org.springframework.data.repository.findByIdOrNull
import org.springframework.stereotype.Service
import java.time.Duration
import java.time.Instant
import java.util.UUID

private val log = KotlinLogging.logger {}

@Service
class CouponIssueService(
    private val eventRepository: EventRepository,
    private val redisStockRepository: RedisStockRepository,
    private val couponIssueTxService: CouponIssueTxService,
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
        val result = redisStockRepository.tryIssueCoupon(eventId, userId, ttlSeconds)
        when (result) {
            IssueResult.ALREADY_ISSUED -> throw BusinessException(ErrorCode.COUPON_ALREADY_ISSUED)
            IssueResult.SOLD_OUT -> throw BusinessException(ErrorCode.EVENT_SOLD_OUT)
            IssueResult.SUCCESS -> Unit
        }

        // 4. DB에 발급 기록 저장 및 보상 처리
        val couponIssue = couponIssueTxService.saveOrCompensate(eventId, userId)

        return CouponIssueResponse.from(couponIssue)
    }
}
