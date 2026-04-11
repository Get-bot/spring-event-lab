package com.beomjin.springeventlab.global.common

import com.beomjin.springeventlab.global.exception.BusinessException
import com.beomjin.springeventlab.global.exception.ErrorCode
import jakarta.persistence.Column
import jakarta.persistence.Embeddable
import java.time.Instant

/**
 * 시작~종료 시각을 표현하는 Value Object.
 *
 * - 불변식: `startedAt < endedAt` (엄격히 이전)
 * - [startedAt]은 경계 포함, [endedAt]은 경계 제외 (`[start, end)` 반개구간)
 * - 여러 도메인(Event, Coupon 유효기간 등)에서 재사용 가능한 공통 기간 타입
 */
@Embeddable
class DateRange(
    startedAt: Instant,
    endedAt: Instant,
) {
    @Column(name = "started_at", nullable = false)
    var startedAt: Instant = startedAt
        protected set

    @Column(name = "ended_at", nullable = false)
    var endedAt: Instant = endedAt
        protected set

    init {
        if (!startedAt.isBefore(endedAt)) {
            throw BusinessException(
                ErrorCode.INVALID_DATE_RANGE,
                "startedAt=$startedAt, endedAt=$endedAt",
            )
        }
    }

    /** 주어진 시각이 기간 내에 포함되는지 (`[startedAt, endedAt)`) */
    fun contains(instant: Instant): Boolean =
        !instant.isBefore(startedAt) && instant.isBefore(endedAt)

    /** 현재 시각이 시작 전인지 */
    fun isUpcoming(now: Instant = Instant.now()): Boolean = now.isBefore(startedAt)

    /** 현재 시각이 진행 중인지 */
    fun isOngoing(now: Instant = Instant.now()): Boolean = contains(now)

    /** 현재 시각이 종료 이후인지 */
    fun isEnded(now: Instant = Instant.now()): Boolean = !endedAt.isAfter(now)

    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other !is DateRange) return false
        return startedAt == other.startedAt && endedAt == other.endedAt
    }

    override fun hashCode(): Int = 31 * startedAt.hashCode() + endedAt.hashCode()

    override fun toString(): String = "DateRange(startedAt=$startedAt, endedAt=$endedAt)"
}
