package com.beomjin.springeventlab.event.entity

import com.beomjin.springeventlab.global.common.BaseTimeEntity
import jakarta.persistence.Column
import jakarta.persistence.Entity
import jakarta.persistence.EnumType
import jakarta.persistence.Enumerated
import jakarta.persistence.Id
import jakarta.persistence.Table
import org.hibernate.annotations.JdbcTypeCode
import org.hibernate.type.SqlTypes
import java.util.UUID
import java.time.Instant

@Entity
@Table(name = "events")
class Event(
    title: String,
    totalQuantity: Int,
    eventStatus: EventStatus,
    startedAt: Instant,
    endedAt: Instant,
) : BaseTimeEntity() {
    @Id
    @JdbcTypeCode(SqlTypes.UUID)
    @Column(updatable = false, nullable = false, comment = "이벤트 PK")
    var id: UUID = UUID.randomUUID()
        protected set

    @Column(nullable = false, length = 200, comment = "이벤트 제목")
    var title: String = title
        protected set

    @Column(nullable = false, comment = "총 수량")
    var totalQuantity: Int = totalQuantity
        protected set

    @Column(nullable = false, comment = "발급된 수량")
    var issuedQuantity: Int = 0
        protected set

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20, comment = "이벤트 상태")
    var eventStatus: EventStatus = eventStatus
        protected set

    @Column(nullable = false, comment = "시작 시각")
    var startedAt: Instant = startedAt
        protected set

    @Column(nullable = false, comment = "종료 시각")
    var endedAt: Instant = endedAt
        protected set

    // --- 도메인 로직 ---

    /** 잔여 수량 계산 */
    val remainingQuantity: Int
        get() = totalQuantity - issuedQuantity

    /** 발급 가능 여부 확인 */
    fun isIssuable(): Boolean = eventStatus == EventStatus.OPEN && remainingQuantity > 0

    /** 쿠폰 1장 발급 처리 (재고 차감) — redis-stock에서 사용 예정 */
    fun issue() {
        check(isIssuable()) { "발급 불가능한 상태입니다. status=$eventStatus, remaining=$remainingQuantity" }
        issuedQuantity++
    }

    /** 이벤트 오픈 */
    fun open() {
        check(eventStatus == EventStatus.READY) { "READY 상태에서만 오픈 가능합니다. current=$eventStatus" }
        eventStatus = EventStatus.OPEN
    }

    /** 이벤트 종료 */
    fun close() {
        check(eventStatus == EventStatus.OPEN) { "OPEN 상태에서만 종료 가능합니다. current=$eventStatus" }
        eventStatus = EventStatus.CLOSED
    }
}
