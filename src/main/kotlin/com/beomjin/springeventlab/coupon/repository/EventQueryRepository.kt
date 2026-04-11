package com.beomjin.springeventlab.coupon.repository

import com.beomjin.springeventlab.coupon.dto.request.EventSearchCond
import com.beomjin.springeventlab.coupon.entity.Event
import com.beomjin.springeventlab.coupon.entity.QEvent.Companion.event
import com.querydsl.core.types.dsl.BooleanExpression
import com.querydsl.jpa.impl.JPAQueryFactory
import org.springframework.data.domain.Page
import org.springframework.data.domain.Pageable
import org.springframework.data.support.PageableExecutionUtils
import org.springframework.stereotype.Repository

@Repository
class EventQueryRepository(
    private val queryFactory: JPAQueryFactory,
) {
    /**
     * 검색 조건 + 페이징/정렬을 적용해 이벤트 목록을 조회한다.
     *
     * - null 조건은 `listOfNotNull`로 걸러져 `.where()`에 전달되지 않음
     * - 정렬은 [EventQuery.orders]로 [Pageable.sort] → [OrderSpecifier] 변환
     * - 전체 건수는 지연 실행되어, 마지막 페이지이거나 한 페이지로 끝나는 경우 count 쿼리 생략
     */
    fun search(
        cond: EventSearchCond,
        pageable: Pageable,
    ): Page<Event> {
        val conditions: Array<BooleanExpression> =
            listOfNotNull(
                EventQuery.keywordMatches(cond.keyword, cond.searchType),
                EventQuery.statusesIn(cond.statuses),
                EventQuery.periodMatches(cond.period),
                EventQuery.createdBetween(cond.createdFrom, cond.createdTo),
                EventQuery.hasRemainingStock(cond.hasRemainingStock),
            ).toTypedArray()

        val content: List<Event> =
            queryFactory
                .selectFrom(event)
                .where(*conditions)
                .offset(pageable.offset)
                .limit(pageable.pageSize.toLong())
                .orderBy(*EventQuery.orders(pageable.sort))
                .fetch()

        val countQuery =
            queryFactory
                .select(event.id.count())
                .from(event)
                .where(*conditions)

        return PageableExecutionUtils.getPage(content, pageable) { countQuery.fetchOne() ?: 0L }
    }
}
