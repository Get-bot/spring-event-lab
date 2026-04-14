package com.beomjin.springeventlab.coupon.dto.response

import com.beomjin.springeventlab.coupon.entity.EventStatus
import com.beomjin.springeventlab.support.EventFixture
import io.kotest.core.spec.style.DescribeSpec
import io.kotest.matchers.shouldBe

class EventResponseTest :
    DescribeSpec({

        describe("from(entity)") {
            it("Entity의 모든 필드를 Response에 매핑한다") {
                val event =
                    EventFixture.event(
                        title = "매핑 테스트",
                        totalQuantity = 200,
                    )
                val response = EventResponse.from(event)

                response.id shouldBe event.id
                response.title shouldBe "매핑 테스트"
                response.totalQuantity shouldBe 200
                response.issuedQuantity shouldBe 0
                response.remainingQuantity shouldBe 200
                response.eventStatus shouldBe EventStatus.READY
                response.startedAt shouldBe event.period.startedAt
                response.endedAt shouldBe event.period.endedAt
            }

            it("remainingQuantity는 totalQuantity - issuedQuantity를 반영한다") {
                val event = EventFixture.openEvent(totalQuantity = 10)
                repeat(3) { event.issue() }

                val response = EventResponse.from(event)
                response.issuedQuantity shouldBe 3
                response.remainingQuantity shouldBe 7
            }
        }
    })
