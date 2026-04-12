package com.beomjin.springeventlab.coupon.entity

import com.beomjin.springeventlab.global.exception.BusinessException
import com.beomjin.springeventlab.global.exception.ErrorCode

enum class EventStatus(
    val description: String,
    val isIssuable: Boolean,
) {
    READY("이벤트 준비 중 (시작 전)", isIssuable = false),
    OPEN("이벤트 진행 중 (발급 가능)", isIssuable = true),
    CLOSED("이벤트 종료", isIssuable = false),
    ;

    // lazy → 최초 1회만 Set 생성, 이후 캐싱
    val allowedTransitions: Set<EventStatus> by lazy {
        when (this) {
            READY -> setOf(OPEN)
            OPEN -> setOf(CLOSED)
            CLOSED -> emptySet()
        }
    }

    fun canTransitionTo(next: EventStatus): Boolean = next in allowedTransitions

    fun transitionTo(next: EventStatus): EventStatus {
        if (!canTransitionTo(next)) {
            throw BusinessException(
                ErrorCode.EVENT_INVALID_STATUS_TRANSITION,
                "[$this → $next] 허용된 전환: $allowedTransitions",
            )
        }
        return next
    }
}
