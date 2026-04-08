package com.beomjin.springeventlab.global.exception

import org.springframework.http.HttpStatus

enum class ErrorCode(
    val status: HttpStatus,
    val code: String,
    val message: String,
) {
    // 400 Bad Request
    INVALID_INPUT(HttpStatus.BAD_REQUEST, "C400", "잘못된 입력입니다."),

    // 401 Unauthorized
    UNAUTHORIZED(HttpStatus.UNAUTHORIZED, "C401", "인증에 실패했습니다."),

    // 403 Forbidden
    FORBIDDEN(HttpStatus.FORBIDDEN, "C403", "접근 권한이 없습니다."),

    // 404 Not Found
    NOT_FOUND(HttpStatus.NOT_FOUND, "C404", "요청한 리소스를 찾을 수 없습니다."),

    // 409 Conflict
    CONFLICT(HttpStatus.CONFLICT, "C409", "이미 존재하는 데이터이거나, 상태가 충돌합니다."),

    // 429 Too Many Requests
    TOO_MANY_REQUESTS(HttpStatus.TOO_MANY_REQUESTS, "C429", "요청이 너무 많습니다. 잠시 후 다시 시도해주세요."),

    // 500 Internal Server Error
    INTERNAL_SERVER_ERROR(HttpStatus.INTERNAL_SERVER_ERROR, "C500", "서버 내부 오류가 발생했습니다."),

    // 502 Bad Gateway
    BAD_GATEWAY(HttpStatus.BAD_GATEWAY, "C502", "외부 서버와 통신 중 오류가 발생했습니다."),

    // 503 Service Unavailable
    SERVICE_UNAVAILABLE(HttpStatus.SERVICE_UNAVAILABLE, "C503", "현재 서비스를 이용할 수 없습니다."),
}
