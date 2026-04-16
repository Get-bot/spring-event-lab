package com.beomjin.springeventlab.global.exception

import com.beomjin.springeventlab.global.exception.ErrorCodeMapper.httpStatus
import com.fasterxml.jackson.databind.exc.InvalidFormatException
import com.fasterxml.jackson.databind.exc.MismatchedInputException
import io.github.oshai.kotlinlogging.KotlinLogging
import org.springframework.dao.DataIntegrityViolationException
import org.springframework.data.redis.RedisConnectionFailureException
import org.springframework.data.redis.RedisSystemException
import org.springframework.http.HttpStatus
import org.springframework.http.ResponseEntity
import org.springframework.http.converter.HttpMessageNotReadableException
import org.springframework.orm.ObjectOptimisticLockingFailureException
import org.springframework.validation.BindException
import org.springframework.validation.BindingResult
import org.springframework.web.bind.MethodArgumentNotValidException
import org.springframework.web.bind.annotation.ExceptionHandler
import org.springframework.web.bind.annotation.RestControllerAdvice
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException
import org.springframework.web.servlet.resource.NoResourceFoundException

private val log = KotlinLogging.logger {}

@RestControllerAdvice
class GlobalExceptionHandler {
    @ExceptionHandler(BusinessException::class)
    fun handleBusiness(e: BusinessException): ResponseEntity<ErrorResponse> {
        log.warn { "Business exception: code=${e.errorCode.code}, message=${e.message}" }
        return ResponseEntity
            .status(e.errorCode.httpStatus())
            .body(ErrorResponse.of(e.errorCode))
    }

    @ExceptionHandler(BindException::class, MethodArgumentNotValidException::class)
    fun handleValidation(e: BindException): ResponseEntity<ErrorResponse> = handleValidationErrors(e.bindingResult)

    @ExceptionHandler(MethodArgumentTypeMismatchException::class)
    fun handleTypeMismatch(e: MethodArgumentTypeMismatchException): ResponseEntity<ErrorResponse> {
        log.warn { "Type mismatch: parameter=${e.name}, value=${e.value}" }
        return ResponseEntity
            .status(HttpStatus.BAD_REQUEST)
            .body(ErrorResponse.of(ErrorCode.INVALID_INPUT))
    }

    @ExceptionHandler(HttpMessageNotReadableException::class)
    fun handleNotReadable(e: HttpMessageNotReadableException): ResponseEntity<ErrorResponse> {
        val cause = e.cause

        if (cause?.cause is BusinessException) {
            val biz = cause.cause as BusinessException
            log.warn { "JSON parse error (business): ${biz.message}" }
            return ResponseEntity.status(biz.errorCode.httpStatus()).body(ErrorResponse.of(biz.errorCode))
        }

        val errorMessage =
            when (cause) {
                is InvalidFormatException -> {
                    val fieldName = cause.path.joinToString(".") { it.fieldName ?: "" }
                    val targetType = cause.targetType
                    if (targetType?.isEnum == true) {
                        val validValues = targetType.enumConstants.joinToString(", ")
                        "Invalid value for '$fieldName'. Accepted values: $validValues"
                    } else {
                        "Invalid format for field '$fieldName'"
                    }
                }

                is MismatchedInputException -> {
                    val fieldName = cause.path.joinToString(".") { it.fieldName ?: "" }
                    "Missing or invalid field '$fieldName'"
                }

                else -> {
                    "Invalid request format"
                }
            }

        log.warn { "JSON parse error: ${e.message}" }
        return ResponseEntity
            .status(HttpStatus.BAD_REQUEST)
            .body(ErrorResponse.of(ErrorCode.INVALID_INPUT, errorMessage))
    }

    @ExceptionHandler(ObjectOptimisticLockingFailureException::class)
    fun handleOptimisticLock(e: ObjectOptimisticLockingFailureException): ResponseEntity<ErrorResponse> {
        log.warn { "Optimistic lock conflict: ${e.message}" }
        return ResponseEntity
            .status(HttpStatus.CONFLICT)
            .body(ErrorResponse.of(ErrorCode.CONFLICT))
    }

    @ExceptionHandler(DataIntegrityViolationException::class)
    fun handleDataIntegrity(e: DataIntegrityViolationException): ResponseEntity<ErrorResponse> {
        log.warn { "Data integrity violation: ${e.message}" }
        return ResponseEntity
            .status(HttpStatus.CONFLICT)
            .body(ErrorResponse.of(ErrorCode.CONFLICT))
    }

    @ExceptionHandler(NoResourceFoundException::class)
    fun handleNoResourceFound(e: NoResourceFoundException): ResponseEntity<ErrorResponse> {
        log.warn { "No resource found: ${e.resourcePath}" }
        return ResponseEntity
            .status(HttpStatus.NOT_FOUND)
            .body(ErrorResponse.of(ErrorCode.NOT_FOUND))
    }

    @ExceptionHandler(Exception::class)
    fun handleUnexpected(e: Exception): ResponseEntity<ErrorResponse> {
        log.error(e) { "Unexpected error" }
        return ResponseEntity
            .status(HttpStatus.INTERNAL_SERVER_ERROR)
            .body(ErrorResponse.of(ErrorCode.INTERNAL_SERVER_ERROR))
    }

    private fun handleValidationErrors(bindingResult: BindingResult): ResponseEntity<ErrorResponse> {
        val errors =
            bindingResult.fieldErrors.associate {
                it.field to (it.defaultMessage ?: "")
            }
        log.warn { "Validation failed: $errors" }
        return ResponseEntity
            .status(HttpStatus.BAD_REQUEST)
            .body(ErrorResponse.of(ErrorCode.INVALID_INPUT, errors))
    }

    @ExceptionHandler(RedisConnectionFailureException::class, RedisSystemException::class)
    fun handleRedisUnavailable(e: Exception): ResponseEntity<ErrorResponse> {
        log.error(e) { "Redis unavailable" }
        return ResponseEntity
            .status(ErrorCode.REDIS_UNAVAILABLE.httpStatus())
            .body(ErrorResponse.of(ErrorCode.REDIS_UNAVAILABLE))
    }
}
