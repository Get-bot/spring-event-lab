package com.beomjin.springeventlab.event.entity

enum class EventStatus(
    val description: String,
) {
    READY("이벤트 준비 중 (시작 전)"),
    OPEN("이벤트 진행 중 (발급 가능)"),
    CLOSED("이벤트 종료"),

    // TODO : 상태전환 머신 구현
}
