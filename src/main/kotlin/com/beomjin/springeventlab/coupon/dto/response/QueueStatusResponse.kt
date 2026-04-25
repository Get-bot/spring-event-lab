package com.beomjin.springeventlab.coupon.dto.response

import io.swagger.v3.oas.annotations.media.Schema
import java.util.UUID

@Schema(description = "대기열 상태 조회 응답")
data class QueueStatusResponse(
    @Schema(description = "현재 상태", example = "WAITING")
    val status: QueueStatus,
    @Schema(description = "WAITING일 때만 채워짐 — 1-based 순번", example = "1234")
    val rank: Long? = null,
    @Schema(description = "WAITING일 때만 채워짐 — 총 대기 인원", example = "5000")
    val totalWaiting: Long? = null,
    @Schema(description = "ISSUED일 때만 채워짐 — couponIssue PK")
    val issueId: UUID? = null,
    @Schema(description = "FAILED일 때만 채워짐 — 실패 사유 (ErrorCode 이름)", example = "COUPON_PUBLISH_FAILED")
    val reason: String? = null,
) {
    companion object {
        /**
         * Redis result 키 페이로드를 파싱.
         * 형식:
         * - "ISSUED:<uuid>"
         * - "SOLD_OUT"
         * - "ALREADY_ISSUED"
         * - "FAILED:<errorCode>"
         */
        fun fromResultPayload(payload: String): QueueStatusResponse {
            val head = payload.substringBefore(':')
            val tail = payload.substringAfter(':', "")
            return when (head) {
                "ISSUED" -> QueueStatusResponse(QueueStatus.ISSUED, issueId = UUID.fromString(tail))
                "SOLD_OUT" -> QueueStatusResponse(QueueStatus.SOLD_OUT)
                "ALREADY_ISSUED" -> QueueStatusResponse(QueueStatus.ALREADY_ISSUED)
                "FAILED" -> QueueStatusResponse(QueueStatus.FAILED, reason = tail)
                else -> QueueStatusResponse(QueueStatus.FAILED, reason = "UNKNOWN_PAYLOAD")
            }
        }
    }
}
