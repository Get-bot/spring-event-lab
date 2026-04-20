package com.beomjin.springeventlab.coupon.dto.message

import java.time.Instant
import java.util.UUID

data class CouponIssueMessage(
    val id: UUID,
    val eventId: UUID,
    val userId: UUID,
    val issuedAt: Instant,
)
