package com.beomjin.springeventlab.global.exception

import com.beomjin.springeventlab.global.exception.ErrorCode.BAD_GATEWAY
import com.beomjin.springeventlab.global.exception.ErrorCode.CONFLICT
import com.beomjin.springeventlab.global.exception.ErrorCode.EVENT_INVALID_STATUS_TRANSITION
import com.beomjin.springeventlab.global.exception.ErrorCode.EVENT_NOT_FOUND
import com.beomjin.springeventlab.global.exception.ErrorCode.EVENT_NOT_OPEN
import com.beomjin.springeventlab.global.exception.ErrorCode.EVENT_OUT_OF_STOCK
import com.beomjin.springeventlab.global.exception.ErrorCode.FORBIDDEN
import com.beomjin.springeventlab.global.exception.ErrorCode.INTERNAL_SERVER_ERROR
import com.beomjin.springeventlab.global.exception.ErrorCode.INVALID_DATE_RANGE
import com.beomjin.springeventlab.global.exception.ErrorCode.INVALID_INPUT
import com.beomjin.springeventlab.global.exception.ErrorCode.NOT_FOUND
import com.beomjin.springeventlab.global.exception.ErrorCode.SERVICE_UNAVAILABLE
import com.beomjin.springeventlab.global.exception.ErrorCode.TOO_MANY_REQUESTS
import com.beomjin.springeventlab.global.exception.ErrorCode.UNAUTHORIZED
import org.springframework.http.HttpStatus

object ErrorCodeMapper {

    fun ErrorCode.httpStatus(): HttpStatus = when (this) {
        INVALID_INPUT, INVALID_DATE_RANGE -> HttpStatus.BAD_REQUEST
        UNAUTHORIZED                      -> HttpStatus.UNAUTHORIZED
        FORBIDDEN                         -> HttpStatus.FORBIDDEN
        NOT_FOUND, EVENT_NOT_FOUND        -> HttpStatus.NOT_FOUND
        CONFLICT, EVENT_NOT_OPEN,
        EVENT_OUT_OF_STOCK,
        EVENT_INVALID_STATUS_TRANSITION   -> HttpStatus.CONFLICT
        TOO_MANY_REQUESTS                 -> HttpStatus.TOO_MANY_REQUESTS
        INTERNAL_SERVER_ERROR             -> HttpStatus.INTERNAL_SERVER_ERROR
        BAD_GATEWAY                       -> HttpStatus.BAD_GATEWAY
        SERVICE_UNAVAILABLE               -> HttpStatus.SERVICE_UNAVAILABLE
    }
}
