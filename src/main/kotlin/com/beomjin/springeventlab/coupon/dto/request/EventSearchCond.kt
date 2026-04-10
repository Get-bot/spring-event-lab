package com.beomjin.springeventlab.coupon.dto.request

import com.beomjin.springeventlab.coupon.entity.EventStatus
import io.swagger.v3.oas.annotations.media.ArraySchema
import io.swagger.v3.oas.annotations.media.Schema
import jakarta.validation.constraints.Size
import java.time.Instant

@Schema(description = "이벤트 목록 조회 조건")
data class EventSearchCond(
    @field:Size(min = 2, message = "검색어는 최소 2자 이상 입력해주세요.")
    @Schema(description = "검색어", example = "여름 쿠폰")
    val keyword: String? = null,

    @Schema(description = "검색어가 매핑될 필드", example = "TITLE", defaultValue = "TITLE")
    val searchType: EventSearchType = EventSearchType.TITLE,

    @ArraySchema(schema = Schema(implementation = EventStatus::class, example = "OPEN"))
    val statuses: List<EventStatus>? = null,

    @Schema(description = "이벤트 기간 필터 (현재 시각 기준)", example = "ONGOING")
    val period: EventPeriod? = null,

    @Schema(description = "생성일 시작 (ISO-8601)", example = "2026-06-01T00:00:00Z")
    val createdFrom: Instant? = null,

    @Schema(description = "생성일 종료 (ISO-8601)", example = "2026-07-31T23:59:59Z")
    val createdTo: Instant? = null,

    @Schema(description = "재고 필터: true=재고 있음, false=소진, null=전체", example = "true")
    val hasRemainingStock: Boolean? = null,
)
