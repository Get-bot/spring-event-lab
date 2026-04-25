package com.beomjin.springeventlab.coupon.service

import com.beomjin.springeventlab.coupon.dto.response.QueueEnterResponse
import com.beomjin.springeventlab.coupon.dto.response.QueueStatus
import com.beomjin.springeventlab.coupon.dto.response.QueueStatusResponse
import com.beomjin.springeventlab.coupon.repository.EnterResult
import com.beomjin.springeventlab.coupon.repository.EventRepository
import com.beomjin.springeventlab.coupon.repository.WaitingQueueRepository
import com.beomjin.springeventlab.global.exception.BusinessException
import com.beomjin.springeventlab.global.exception.ErrorCode
import org.springframework.data.repository.findByIdOrNull
import org.springframework.stereotype.Service
import java.time.Duration
import java.time.Instant
import java.util.UUID

@Service
class WaitingQueueService(
    private val eventRepository: EventRepository,
    private val waitingQueueRepository: WaitingQueueRepository,
) {
    /**
     * 대기열 진입.
     *
     * 1. 이벤트 존재/시간 검증
     * 2. Lua EVAL — 발급/큐 중복 체크 + ZADD를 원자 처리
     * 3. ZRANK + ZCARD로 순번/총원 조회
     */
    fun enter(
        eventId: UUID,
        userId: UUID,
    ): QueueEnterResponse {
        val event =
            eventRepository.findByIdOrNull(eventId)
                ?: throw BusinessException(ErrorCode.EVENT_NOT_FOUND)

        val now = Instant.now()
        if (!event.period.contains(now)) {
            throw BusinessException(ErrorCode.EVENT_NOT_OPEN)
        }

        val ttlSeconds =
            Duration
                .between(now, event.period.endedAt)
                .plusHours(1)
                .toSeconds()

        when (waitingQueueRepository.tryEnter(eventId, userId, now.toEpochMilli(), ttlSeconds)) {
            EnterResult.ALREADY_ISSUED -> throw BusinessException(ErrorCode.COUPON_ALREADY_ISSUED)
            EnterResult.ALREADY_IN_QUEUE -> throw BusinessException(ErrorCode.USER_ALREADY_IN_QUEUE)
            EnterResult.SUCCESS -> Unit
        }

        return QueueEnterResponse(
            status = QueueStatus.WAITING,
            rank = waitingQueueRepository.rank(eventId, userId),
            totalWaiting = waitingQueueRepository.size(eventId),
        )
    }

    /**
     * 대기열 상태 / 발급 결과 조회.
     *
     * 우선순위: result 키 → ZRANK → NOT_IN_QUEUE
     */
    fun status(
        eventId: UUID,
        userId: UUID,
    ): QueueStatusResponse {
        // 1) 결과 키 우선 — dequeue 후 처리 결과
        waitingQueueRepository.findResult(eventId, userId)?.let { payload ->
            return QueueStatusResponse.fromResultPayload(payload)
        }

        // 2) 큐에 잔존 — WAITING + rank/total
        val rank = waitingQueueRepository.rank(eventId, userId)
        return if (rank != null) {
            QueueStatusResponse(
                status = QueueStatus.WAITING,
                rank = rank,
                totalWaiting = waitingQueueRepository.size(eventId),
            )
        } else {
            QueueStatusResponse(status = QueueStatus.NOT_IN_QUEUE)
        }
    }
}
