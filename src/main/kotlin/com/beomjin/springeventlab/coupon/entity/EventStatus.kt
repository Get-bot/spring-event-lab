package com.beomjin.springeventlab.event.entity

import com.beomjin.springeventlab.global.exception.BusinessException
import com.beomjin.springeventlab.global.exception.ErrorCode

enum class EventStatus(
    val description: String,
    val isIssuable: Boolean,
    private val allowedTransitions: () -> Set<EventStatus>,
) {
    READY("이벤트 준비 중 (시작 전)", isIssuable = false, { setOf(OPEN) }),
    OPEN("이벤트 진행 중 (발급 가능)", isIssuable = true, { setOf(CLOSED) }),
    CLOSED("이벤트 종료", isIssuable = false, { emptySet() }),
    ;

    fun canTransitionTo(next: EventStatus): Boolean = next in allowedTransitions()

    fun transitionTo(next: EventStatus): EventStatus {
        if (!canTransitionTo(next)) {
            throw BusinessException(
                ErrorCode.EVENT_INVALID_STATUS_TRANSITION,
                "[$this → $next] 허용된 전환: ${allowedTransitions()}",
            )
        }
        return next
    }
}
