package com.beomjin.springeventlab.support

import com.beomjin.springeventlab.coupon.dto.request.EventCreateRequest
import com.beomjin.springeventlab.coupon.dto.response.EventResponse
import com.beomjin.springeventlab.coupon.entity.Event
import com.beomjin.springeventlab.coupon.entity.EventStatus
import com.beomjin.springeventlab.global.common.DateRange
import java.time.Instant

object EventFixture {
    val DEFAULT_START: Instant = Instant.parse("2026-07-01T00:00:00Z")
    val DEFAULT_END: Instant = Instant.parse("2026-07-07T23:59:59Z")

    fun dateRange(
        start: Instant = DEFAULT_START,
        end: Instant = DEFAULT_END,
    ) = DateRange(start, end)

    fun event(
        title: String = "여름 쿠폰 이벤트",
        totalQuantity: Int = 100,
        status: EventStatus = EventStatus.READY,
        period: DateRange = dateRange(),
    ) = Event(title, totalQuantity, status, period)

    fun openEvent(
        title: String = "진행중 이벤트",
        totalQuantity: Int = 100,
        period: DateRange = dateRange(),
    ): Event =
        event(title = title, totalQuantity = totalQuantity, status = EventStatus.READY, period = period)
            .also { it.open() }

    fun createRequest(
        title: String = "여름 쿠폰 이벤트",
        totalQuantity: Int? = 100,
        startedAt: Instant? = DEFAULT_START,
        endedAt: Instant? = DEFAULT_END,
    ) = EventCreateRequest(title, totalQuantity, startedAt, endedAt)

    fun response(event: Event = event()) = EventResponse.from(event)

    fun createRequestJson(
        title: String = "여름 쿠폰 이벤트",
        totalQuantity: Int = 100,
        startedAt: String = DEFAULT_START.toString(),
        endedAt: String = DEFAULT_END.toString(),
    ): String =
        """
        {
          "title": "$title",
          "totalQuantity": $totalQuantity,
          "startedAt": "$startedAt",
          "endedAt": "$endedAt"
        }
        """.trimIndent()
}
