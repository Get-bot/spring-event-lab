package com.beomjin.springeventlab.event.entity

import com.beomjin.springeventlab.global.exception.BusinessException
import com.beomjin.springeventlab.global.exception.ErrorCode

enum class EventStatus(
    val description: String,
    private val allowedTransitions: () -> Set<EventStatus>,
) {
    READY("이벤트 준비 중 (시작 전)", { setOf(OPEN) }),
    OPEN("이벤트 진행 중 (발급 가능)", { setOf(CLOSED) }),
    CLOSED("이벤트 종료", { emptySet() }),
    ;

    fun canTransitionTo(next: EventStatus): Boolean = next in allowedTransitions()

    fun transitionTo(next: EventStatus): EventStatus {
        if (!canTransitionTo(next)) {
            throw BusinessException(
                ErrorCode.CONFLICT,
                "[$this → $next] 상태 전환 불가. 허용된 전환: ${allowedTransitions()}",
            )
        }
        return next
    }
}
