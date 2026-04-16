package com.beomjin.springeventlab.coupon.service

import com.beomjin.springeventlab.coupon.entity.CouponIssue
import com.beomjin.springeventlab.coupon.repository.CouponIssueRepository
import com.beomjin.springeventlab.coupon.repository.RedisStockRepository
import com.beomjin.springeventlab.global.exception.BusinessException
import com.beomjin.springeventlab.global.exception.ErrorCode
import io.github.oshai.kotlinlogging.KotlinLogging
import org.springframework.dao.DataAccessException
import org.springframework.dao.DataIntegrityViolationException
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import java.util.UUID

private val log = KotlinLogging.logger {}

@Service
class CouponIssueTxService(
    private val couponIssueRepository: CouponIssueRepository,
    private val redisStockRepository: RedisStockRepository,
) {
    @Transactional
    fun saveOrCompensate(
        eventId: UUID,
        userId: UUID,
    ): CouponIssue {
        try {
            return couponIssueRepository.saveAndFlush(
                CouponIssue(eventId = eventId, userId = userId),
            )
        } catch (e: DataIntegrityViolationException) {
            // UK 위반 = Redis는 정확, DB에 이미 존재
            // issued Set은 유지하되 stock만 복원 (INCR만)
            log.warn { "UK 위반 — 이미 발급된 쿠폰 (eventId=$eventId, userId=$userId)" }
            redisStockRepository.restoreStock(eventId)
            throw BusinessException(ErrorCode.COUPON_ALREADY_ISSUED)
        } catch (e: DataAccessException) {
            // 기타 DB 오류 = Redis 상태를 완전 롤백
            log.error(e) { "DB 저장 실패, Redis 보상 처리 - eventId=$eventId, userId=$userId" }
            redisStockRepository.compensate(eventId, userId)
            throw e
        }
    }
}
