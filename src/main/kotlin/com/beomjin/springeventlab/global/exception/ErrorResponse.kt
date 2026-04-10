package com.beomjin.springeventlab.global.exception

import java.time.Instant

data class ErrorResponse(
    val code: String,
    val message: String,
    val errors: Map<String, String> = emptyMap(),
    val timestamp: Instant = Instant.now(),
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
