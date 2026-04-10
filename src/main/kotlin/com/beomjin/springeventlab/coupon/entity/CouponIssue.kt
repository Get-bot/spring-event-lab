package com.beomjin.springeventlab.coupon.entity

import com.beomjin.springeventlab.global.common.BaseCreatedTimeEntity
import com.github.f4b6a3.uuid.UuidCreator
import jakarta.persistence.Column
import jakarta.persistence.Entity
import jakarta.persistence.Id
import jakarta.persistence.Table
import org.hibernate.annotations.JdbcTypeCode
import org.hibernate.type.SqlTypes
import java.util.UUID

@Entity
@Table(name = "coupon_issue")
class CouponIssue(
    eventId: UUID,
    userId: UUID,
) : BaseCreatedTimeEntity() {
    @Id
    @JdbcTypeCode(SqlTypes.UUID)
    @Column(updatable = false, nullable = false, comment = "쿠폰_발급 PK")
    var id: UUID = UuidCreator.getTimeOrderedEpoch()
        protected set

    @Column(nullable = false, comment = "이벤트 ID")
    var eventId: UUID = eventId
        protected set

    @Column(nullable = false, comment = "사용자 ID")
    var userId: UUID = userId
        protected set
}
