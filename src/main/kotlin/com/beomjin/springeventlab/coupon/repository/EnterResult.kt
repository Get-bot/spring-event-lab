package com.beomjin.springeventlab.coupon.repository

enum class EnterResult(val code: Long) {
    ALREADY_ISSUED(-1),
    ALREADY_IN_QUEUE(0),
    SUCCESS(1),
    ;

    companion object {
        private val CODE_MAP = entries.associateBy { it.code }

        fun fromCode(code: Long): EnterResult =
            CODE_MAP[code] ?: throw IllegalStateException("Unknown Lua script return code: $code")
    }
}
