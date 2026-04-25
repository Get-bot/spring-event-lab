package com.beomjin.springeventlab.coupon.scheduler

import com.beomjin.springeventlab.coupon.repository.EventRepository
import com.beomjin.springeventlab.coupon.repository.WaitingQueueRepository
import com.beomjin.springeventlab.coupon.service.CouponIssueService
import com.beomjin.springeventlab.global.config.WaitingQueueProperties
import com.beomjin.springeventlab.global.exception.BusinessException
import com.beomjin.springeventlab.global.exception.ErrorCode
import io.github.oshai.kotlinlogging.KotlinLogging
import org.springframework.scheduling.annotation.Scheduled
import org.springframework.stereotype.Component
import java.time.Duration
import java.time.Instant
import java.util.UUID

private val log = KotlinLogging.logger {}

@Component
class CouponIssueScheduler(
    private val eventRepository: EventRepository,
    private val waitingQueueRepository: WaitingQueueRepository,
    private val couponIssueService: CouponIssueService,
    private val waitingQueueProperties: WaitingQueueProperties,
) {
    /**
     * 진행 중인 모든 이벤트의 큐를 일정 속도로 drain.
     * fixedDelay: 이전 실행 종료 + N ms 후 다음 실행 (back-pressure 친화).
     */
    @Scheduled(fixedDelayString = "\${waiting-queue.poll-interval-ms:1000}")
    fun drainQueues() {
        val openEvents = eventRepository.findAllOpenAt(Instant.now())
        if (openEvents.isEmpty()) return

        for (event in openEvents) {
            drainOneEvent(event.id)
        }
    }

    private fun drainOneEvent(eventId: UUID) {
        val poppedUserIds =
            waitingQueueRepository.popMin(eventId, waitingQueueProperties.batchSize.toLong())
        if (poppedUserIds.isEmpty()) return

        val resultTtl = Duration.ofSeconds(waitingQueueProperties.resultTtlSeconds)

        for (userId in poppedUserIds) {
            try {
                val response = couponIssueService.issue(eventId, userId)
                waitingQueueRepository.recordResult(
                    eventId, userId, "ISSUED:${response.id}", resultTtl,
                )
            } catch (e: BusinessException) {
                handleBusinessException(eventId, userId, e, resultTtl)
                if (e.errorCode == ErrorCode.EVENT_SOLD_OUT) {
                    drainRemainingAsSoldOut(eventId, poppedUserIds, userId, resultTtl)
                    return
                }
            } catch (e: Exception) {
                log.error(e) { "Unexpected drain failure — eventId=$eventId userId=$userId" }
                waitingQueueRepository.recordResult(eventId, userId, "FAILED:UNKNOWN", resultTtl)
            }
        }
    }

    private fun handleBusinessException(
        eventId: UUID,
        userId: UUID,
        e: BusinessException,
        ttl: Duration,
    ) {
        val payload =
            when (e.errorCode) {
                ErrorCode.EVENT_SOLD_OUT -> "SOLD_OUT"
                ErrorCode.COUPON_ALREADY_ISSUED -> "ALREADY_ISSUED"
                else -> "FAILED:${e.errorCode.name}"
            }
        waitingQueueRepository.recordResult(eventId, userId, payload, ttl)
    }

    /**
     * 첫 SOLD_OUT 이후 batch 잔여 유저는 어차피 같은 결과 — Lua 호출 없이 SET만으로 일괄 통보.
     */
    private fun drainRemainingAsSoldOut(
        eventId: UUID,
        popped: List<UUID>,
        currentUserId: UUID,
        ttl: Duration,
    ) {
        val idx = popped.indexOf(currentUserId)
        if (idx < 0 || idx == popped.lastIndex) return
        for (userId in popped.subList(idx + 1, popped.size)) {
            waitingQueueRepository.recordResult(eventId, userId, "SOLD_OUT", ttl)
        }
    }
}
