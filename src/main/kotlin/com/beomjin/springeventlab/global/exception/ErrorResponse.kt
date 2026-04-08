package com.beomjin.springeventlab.global.exception

import java.time.LocalDateTime

data class ErrorResponse(
    val code: String,
    val message: String,
    val errors: Map<String, String> = emptyMap(),
    val timestamp: LocalDateTime = LocalDateTime.now(),
) {
    companion object {
        fun of(errorCode: ErrorCode) =
            ErrorResponse(
                code = errorCode.code,
                message = errorCode.message,
            )

        fun of(
            errorCode: ErrorCode,
            errors: Map<String, String>,
        ) = ErrorResponse(
            code = errorCode.code,
            message = errorCode.message,
            errors = errors,
        )

        fun of(
            errorCode: ErrorCode,
            detail: String,
        ) = ErrorResponse(
            code = errorCode.code,
            message = detail,
        )
    }
}
