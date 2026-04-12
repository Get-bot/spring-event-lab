package com.beomjin.springeventlab.coupon.entity

import com.beomjin.springeventlab.global.common.BaseTimeEntity
import com.beomjin.springeventlab.global.common.DateRange
import com.beomjin.springeventlab.global.exception.BusinessException
import com.beomjin.springeventlab.global.exception.ErrorCode
import com.github.f4b6a3.uuid.UuidCreator
import jakarta.persistence.Column
import jakarta.persistence.Embedded
import jakarta.persistence.Entity
import jakarta.persistence.EnumType
import jakarta.persistence.Enumerated
import jakarta.persistence.Id
import jakarta.persistence.Table
import org.hibernate.annotations.JdbcTypeCode
import org.hibernate.type.SqlTypes
import java.util.UUID

@Entity
@Table(name = "event")
class Event(
    title: String,
    totalQuantity: Int,
    eventStatus: EventStatus,
    period: DateRange,
) : BaseTimeEntity() {
    @Id
    @JdbcTypeCode(SqlTypes.UUID)
    @Column(updatable = false, nullable = false, comment = "이벤트 PK")
    var id: UUID = UuidCreator.getTimeOrderedEpoch()
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

    @Embedded
    var period: DateRange = period
        protected set

    // --- 도메인 로직 ---

    val remainingQuantity: Int
        get() = totalQuantity - issuedQuantity

    /**
     * 쿠폰 1장 발급 처리 (재고 차감) — redis-stock에서 사용 예정.
     * 실패 원인을 구분해서 예외를 던진다:
     * - 상태가 발급 허용이 아님 → [ErrorCode.EVENT_NOT_OPEN]
     * - 재고 소진 → [ErrorCode.EVENT_OUT_OF_STOCK]
     */
    fun issue() {
        if (!eventStatus.isIssuable) {
            throw BusinessException(ErrorCode.EVENT_NOT_OPEN, "status=$eventStatus")
        }
        if (remainingQuantity <= 0) {
            throw BusinessException(
                ErrorCode.EVENT_OUT_OF_STOCK,
                "total=$totalQuantity, issued=$issuedQuantity",
            )
        }
        issuedQuantity++
    }

    fun open() {
        eventStatus = eventStatus.transitionTo(EventStatus.OPEN)
    }

    fun close() {
        eventStatus = eventStatus.transitionTo(EventStatus.CLOSED)
    }
}
