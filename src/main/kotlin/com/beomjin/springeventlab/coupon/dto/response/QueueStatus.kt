package com.beomjin.springeventlab.coupon.dto.response

import io.swagger.v3.oas.annotations.media.Schema

@Schema(description = "대기열 상태")
enum class QueueStatus {
    /** 큐 안에서 대기 중 */
    WAITING,

    /** 발급 성공 */
    ISSUED,

    /** 매진 — 해당 batch에서 stock 소진 */
    SOLD_OUT,

    /** 이미 발급된 유저 (이전 batch에서 처리) */
    ALREADY_ISSUED,

    /** 기타 실패 — reason 필드에 errorCode 전달 */
    FAILED,

    /** 큐에도 없고 결과도 없음 (TTL 만료 또는 미진입) */
    NOT_IN_QUEUE,
}
