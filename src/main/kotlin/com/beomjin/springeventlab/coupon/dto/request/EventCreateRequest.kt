package com.beomjin.springeventlab.coupon.dto.request

import com.beomjin.springeventlab.coupon.entity.Event
import com.beomjin.springeventlab.coupon.entity.EventStatus
import com.beomjin.springeventlab.global.common.DateRange
import io.swagger.v3.oas.annotations.media.Schema
import io.swagger.v3.oas.annotations.media.Schema.RequiredMode.REQUIRED
import jakarta.validation.constraints.Min
import jakarta.validation.constraints.NotBlank
import jakarta.validation.constraints.NotNull
import jakarta.validation.constraints.Size
import java.time.Instant

@Schema(description = "이벤트 생성 요청")
data class EventCreateRequest(
    @field:NotBlank(message = "이벤트 제목은 필수입니다")
    @field:Size(max = 200, message = "이벤트 제목은 200자 이하여야 합니다")
    @Schema(description = "이벤트 제목", example = "2026년 여름 쿠폰 이벤트", requiredMode = REQUIRED)
    val title: String,
    @field:NotNull(message = "총 수량은 필수입니다")
    @field:Min(value = 1, message = "총 수량은 1 이상이어야 합니다")
    @Schema(description = "총 발급 수량 (1 이상)", example = "1000", requiredMode = REQUIRED)
    val totalQuantity: Int?,
    @field:NotNull(message = "시작 시각은 필수입니다")
    @Schema(description = "이벤트 시작 시각 (ISO-8601, UTC)", example = "2026-07-01T00:00:00Z", requiredMode = REQUIRED)
    val startedAt: Instant?,
    @field:NotNull(message = "종료 시각은 필수입니다")
    @Schema(description = "이벤트 종료 시각 (ISO-8601, UTC)", example = "2026-07-07T23:59:59Z", requiredMode = REQUIRED)
    val endedAt: Instant?,
) {
    fun toEntity(): Event =
        Event(
            title = title,
            totalQuantity = totalQuantity!!,
            eventStatus = EventStatus.READY,
            period = DateRange(startedAt!!, endedAt!!),
        )
}
