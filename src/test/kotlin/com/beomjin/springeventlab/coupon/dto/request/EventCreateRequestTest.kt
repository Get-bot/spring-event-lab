package com.beomjin.springeventlab.coupon.dto.request

import com.beomjin.springeventlab.coupon.entity.EventStatus
import com.beomjin.springeventlab.global.exception.BusinessException
import com.beomjin.springeventlab.global.exception.ErrorCode
import com.beomjin.springeventlab.support.EventFixture
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.spec.style.DescribeSpec
import io.kotest.matchers.shouldBe

class EventCreateRequestTest :
    DescribeSpec({

        describe("toEntity()") {
            it("모든 필드가 Entity에 매핑된다") {
                val request =
                    EventFixture.createRequest(
                        title = "테스트 이벤트",
                        totalQuantity = 50,
                    )
                val entity = request.toEntity()

                entity.title shouldBe "테스트 이벤트"
                entity.totalQuantity shouldBe 50
                entity.eventStatus shouldBe EventStatus.READY
                entity.period.startedAt shouldBe EventFixture.DEFAULT_START
                entity.period.endedAt shouldBe EventFixture.DEFAULT_END
            }

            it("DateRange 불변식 위반 시 INVALID_DATE_RANGE를 던진다") {
                val request =
                    EventFixture.createRequest(
                        startedAt = EventFixture.DEFAULT_END,
                        endedAt = EventFixture.DEFAULT_START,
                    )
                shouldThrow<BusinessException> { request.toEntity() }
                    .errorCode shouldBe ErrorCode.INVALID_DATE_RANGE
            }
        }
    })
