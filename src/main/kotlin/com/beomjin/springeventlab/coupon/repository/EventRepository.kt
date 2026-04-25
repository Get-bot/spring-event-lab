package com.beomjin.springeventlab.coupon.repository

import com.beomjin.springeventlab.coupon.entity.Event
import org.springframework.data.jpa.repository.JpaRepository
import org.springframework.data.jpa.repository.Query
import org.springframework.data.repository.query.Param
import org.springframework.stereotype.Repository
import java.time.Instant
import java.util.UUID

@Repository
interface EventRepository : JpaRepository<Event, UUID> {
    /**
     * 현재 시각이 period 내([startedAt, endedAt))이고 status=OPEN인 이벤트 — Scheduler가 매 tick마다 호출.
     */
    @Query(
        """
        SELECT e FROM Event e
        WHERE e.eventStatus = com.beomjin.springeventlab.coupon.entity.EventStatus.OPEN
          AND e.period.startedAt <= :now
          AND e.period.endedAt > :now
        """,
    )
    fun findAllOpenAt(
        @Param("now") now: Instant,
    ): List<Event>
}
