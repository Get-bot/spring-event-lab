package com.beomjin.springeventlab.coupon.dto.response

import io.swagger.v3.oas.annotations.media.Schema

@Schema(description = "대기열 진입 응답")
data class QueueEnterResponse(
    @Schema(description = "현재 상태", example = "WAITING")
    val status: QueueStatus,
    @Schema(description = "현재 순번 (1-based)", example = "1234")
    val rank: Long?,
    @Schema(description = "총 대기 인원", example = "5000")
    val totalWaiting: Long?,
)
