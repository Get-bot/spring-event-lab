package com.beomjin.springeventlab.coupon.dto.request

enum class EventPeriod(
    val description: String,
) {
    UPCOMING("예정 (시작 전)"),
    ONGOING("진행 중 (시작됨, 종료 전)"),
    ENDED("종료됨"),
}
