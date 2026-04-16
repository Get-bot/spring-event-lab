package com.beomjin.springeventlab.global.exception

enum class ErrorCode(
    val code: String,
    val message: String,
) {
    // Common Errors
    INVALID_INPUT("C400", "잘못된 입력입니다."),
    INVALID_DATE_RANGE("C400-1", "잘못된 기간입니다. 시작 시각은 종료 시각보다 이전이어야 합니다."),
    UNAUTHORIZED("C401", "인증에 실패했습니다."),
    FORBIDDEN("C403", "접근 권한이 없습니다."),
    NOT_FOUND("C404", "요청한 리소스를 찾을 수 없습니다."),
    CONFLICT("C409", "이미 존재하는 데이터이거나, 상태가 충돌합니다."),
    TOO_MANY_REQUESTS("C429", "요청이 너무 많습니다. 잠시 후 다시 시도해주세요."),
    INTERNAL_SERVER_ERROR("C500", "서버 내부 오류가 발생했습니다."),
    BAD_GATEWAY("C502", "외부 서버와 통신 중 오류가 발생했습니다."),
    SERVICE_UNAVAILABLE("C503", "현재 서비스를 이용할 수 없습니다."),

    // Event Errors
    EVENT_NOT_FOUND("E404", "이벤트를 찾을 수 없습니다."),
    EVENT_NOT_OPEN("E409-1", "진행 중인 이벤트가 아닙니다."),
    EVENT_OUT_OF_STOCK("E409-2", "재고가 소진되었습니다."),
    EVENT_INVALID_STATUS_TRANSITION("E409-3", "허용되지 않은 이벤트 상태 전환입니다."),
    EVENT_SOLD_OUT("E410", "이벤트 재고가 소진되었습니다."),

    // Coupon Errors
    COUPON_ALREADY_ISSUED("CI409-1", "이미 발급된 쿠폰입니다."),

    // Redis Errors
    REDIS_UNAVAILABLE("R503", "Redis 서비스에 일시적으로 접근할 수 없습니다."),
}
