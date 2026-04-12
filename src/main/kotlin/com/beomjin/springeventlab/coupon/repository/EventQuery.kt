package com.beomjin.springeventlab.coupon.repository

import com.beomjin.springeventlab.coupon.dto.request.EventPeriod
import com.beomjin.springeventlab.coupon.dto.request.EventSearchType
import com.beomjin.springeventlab.coupon.entity.EventStatus
import com.beomjin.springeventlab.coupon.entity.QEvent.Companion.event
import com.querydsl.core.types.OrderSpecifier
import com.querydsl.core.types.dsl.BooleanExpression
import com.querydsl.core.types.dsl.ComparableExpressionBase
import org.springframework.data.domain.Sort
import java.time.Instant

object EventQuery {
    // ---------- Where 조건 ----------

    /**
     * 검색어 + 검색유형 기반 필터.
     */
    fun keywordMatches(
        keyword: String?,
        type: EventSearchType,
    ): BooleanExpression? {
        val kw = keyword?.takeIf { it.isNotBlank() } ?: return null
        return when (type) {
            EventSearchType.TITLE -> event.title.contains(kw)
        }
    }

    /** 상태 IN 필터 */
    fun statusesIn(statuses: List<EventStatus>?): BooleanExpression? =
        statuses?.takeIf { it.isNotEmpty() }?.let { event.eventStatus.`in`(it) }

    /** 기간 필터 (현재 시각 기준) */
    fun periodMatches(
        period: EventPeriod?,
        now: Instant = Instant.now(),
    ): BooleanExpression? =
        when (period) {
            null -> {
                null
            }

            EventPeriod.UPCOMING -> {
                event.period.startedAt.gt(now)
            }

            EventPeriod.ONGOING -> {
                event.period.startedAt
                    .loe(now)
                    .and(event.period.endedAt.gt(now))
            }

            EventPeriod.ENDED -> {
                event.period.endedAt.loe(now)
            }
        }

    /** 생성일 범위 필터 (from/to 모두 선택적) */
    fun createdBetween(
        from: Instant?,
        to: Instant?,
    ): BooleanExpression? =
        when {
            from != null && to != null -> event.createdAt.between(from, to)
            from != null -> event.createdAt.goe(from)
            to != null -> event.createdAt.loe(to)
            else -> null
        }

    /** 재고 필터 — true: 재고 있음, false: 소진, null: 전체 */
    fun hasRemainingStock(hasStock: Boolean?): BooleanExpression? =
        when (hasStock) {
            true -> event.issuedQuantity.lt(event.totalQuantity)
            false -> event.issuedQuantity.goe(event.totalQuantity)
            null -> null
        }

    // ---------- 정렬 ----------

    private val sortableFields: Map<String, ComparableExpressionBase<*>> =
        mapOf(
            "createdAt" to event.createdAt,
            "title" to event.title,
            "startedAt" to event.period.startedAt,
            "endedAt" to event.period.endedAt,
            "totalQuantity" to event.totalQuantity,
        )

    /**
     * Spring Data [org.springframework.data.domain.Sort] → QueryDSL [com.querydsl.core.types.OrderSpecifier] 배열.
     * 허용되지 않은 필드는 조용히 무시, 빈 정렬이면 `createdAt DESC` 기본값.
     */
    fun orders(sort: Sort): Array<OrderSpecifier<*>> =
        sort
            .mapNotNull { o ->
                sortableFields[o.property]?.let { if (o.isAscending) it.asc() else it.desc() }
            }.toTypedArray()
            .ifEmpty { arrayOf(event.createdAt.desc()) }
}
