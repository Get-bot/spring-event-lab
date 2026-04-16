package com.beomjin.springeventlab.coupon.dto.response

import com.beomjin.springeventlab.coupon.entity.CouponIssue
import java.time.Instant
import java.util.UUID

data class CouponIssueResponse(
    val id: UUID,
    val eventId: UUID,
    val userId: UUID,
    val createdAt: Instant?,
) {
    companion object {
        fun from(entity: CouponIssue): CouponIssueResponse =
            CouponIssueResponse(
                id = entity.id,
                eventId = entity.eventId,
                userId = entity.userId,
                createdAt = entity.createdAt,
            )
    }
}
