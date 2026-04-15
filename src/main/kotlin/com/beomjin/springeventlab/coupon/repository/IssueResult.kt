package com.beomjin.springeventlab.coupon.repository

enum class IssueResult(val code: Long) {
    ALREADY_ISSUED(-1),
    SOLD_OUT(0),
    SUCCESS(1),
    ;

    companion object {
        private val CODE_MAP = entries.associateBy { it.code }

        fun fromCode(code: Long): IssueResult =
            CODE_MAP[code] ?: throw IllegalStateException("Unknown Lua script return code: $code")
    }
}
