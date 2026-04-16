package com.beomjin.springeventlab.coupon.controller

import com.beomjin.springeventlab.coupon.dto.response.CouponIssueResponse
import com.beomjin.springeventlab.coupon.service.CouponIssueService
import io.swagger.v3.oas.annotations.Operation
import io.swagger.v3.oas.annotations.tags.Tag
import org.springframework.http.HttpStatus
import org.springframework.http.ResponseEntity
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RequestParam
import org.springframework.web.bind.annotation.RestController
import java.util.UUID

@RestController
@RequestMapping("/api/v1/events")
@Tag(name = "Coupon Issue", description = "쿠폰 발급 API")
class CouponIssueController(
    private val couponIssueService: CouponIssueService,
) {
    @PostMapping("/{eventId}/issue")
    @Operation(
        summary = "쿠폰 발급",
        description = "선착순 쿠폰을 발급합니다. Redis에서 재고를 차감하고 DB에 기록합니다.",
    )
    fun issue(
        @PathVariable eventId: UUID,
        @RequestParam userId: UUID,
    ): ResponseEntity<CouponIssueResponse> {
        val response = couponIssueService.issue(eventId, userId)
        return ResponseEntity.status(HttpStatus.CREATED).body(response)
    }
}
