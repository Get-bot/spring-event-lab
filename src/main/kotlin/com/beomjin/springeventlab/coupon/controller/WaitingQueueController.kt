package com.beomjin.springeventlab.coupon.controller

import com.beomjin.springeventlab.coupon.dto.response.QueueEnterResponse
import com.beomjin.springeventlab.coupon.dto.response.QueueStatusResponse
import com.beomjin.springeventlab.coupon.service.WaitingQueueService
import io.swagger.v3.oas.annotations.Operation
import io.swagger.v3.oas.annotations.tags.Tag
import org.springframework.http.ResponseEntity
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RequestParam
import org.springframework.web.bind.annotation.RestController
import java.util.UUID

@RestController
@RequestMapping("/api/v1/events")
@Tag(name = "Waiting Queue", description = "쿠폰 발급 대기열 API")
class WaitingQueueController(
    private val waitingQueueService: WaitingQueueService,
) {
    @PostMapping("/{eventId}/enter")
    @Operation(
        summary = "대기열 진입",
        description = "Sorted Set에 userId를 ZADD하고 현재 순번을 반환합니다.",
    )
    fun enter(
        @PathVariable eventId: UUID,
        @RequestParam userId: UUID,
    ): ResponseEntity<QueueEnterResponse> = ResponseEntity.ok(waitingQueueService.enter(eventId, userId))

    @GetMapping("/{eventId}/queue/status")
    @Operation(
        summary = "대기열 상태 조회",
        description = "현재 순번 또는 발급 결과를 조회합니다.",
    )
    fun status(
        @PathVariable eventId: UUID,
        @RequestParam userId: UUID,
    ): ResponseEntity<QueueStatusResponse> = ResponseEntity.ok(waitingQueueService.status(eventId, userId))
}
