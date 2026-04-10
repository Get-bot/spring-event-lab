package com.beomjin.springeventlab.coupon.dto.response

import com.beomjin.springeventlab.coupon.entity.Event
import com.beomjin.springeventlab.coupon.entity.EventStatus
import io.swagger.v3.oas.annotations.media.Schema
import java.time.Instant
import java.util.UUID

@Schema(description = "이벤트 응답")
data class EventResponse(
    @Schema(description = "이벤트 ID (UUID v7)", example = "019644a2-3b00-7f8a-a1e2-4c5d6e7f8a9b")
    val id: UUID,
    @Schema(description = "이벤트 제목", example = "2026년 여름 쿠폰 이벤트")
    val title: String,
    @Schema(description = "총 발급 수량", example = "1000")
    val totalQuantity: Int,
    @Schema(description = "현재까지 발급된 수량", example = "235")
    val issuedQuantity: Int,
    @Schema(description = "잔여 수량", example = "765")
    val remainingQuantity: Int,
    @Schema(description = "이벤트 상태", example = "OPEN")
    val eventStatus: EventStatus,
    @Schema(description = "이벤트 시작 시각", example = "2026-07-01T00:00:00Z")
    val startedAt: Instant,
    @Schema(description = "이벤트 종료 시각", example = "2026-07-07T23:59:59Z")
    val endedAt: Instant,
    @Schema(description = "생성 시각", example = "2026-06-25T10:15:30Z")
    val createdAt: Instant?,
    @Schema(description = "마지막 수정 시각", example = "2026-06-25T10:15:30Z")
    val updatedAt: Instant?,
) {
    companion object {
        fun from(event: Event): EventResponse =
            EventResponse(
                id = event.id,
                title = event.title,
                totalQuantity = event.totalQuantity,
                issuedQuantity = event.issuedQuantity,
                remainingQuantity = event.remainingQuantity,
                eventStatus = event.eventStatus,
                startedAt = event.period.startedAt,
                endedAt = event.period.endedAt,
                createdAt = event.createdAt,
                updatedAt = event.updatedAt,
            )
    }
}
