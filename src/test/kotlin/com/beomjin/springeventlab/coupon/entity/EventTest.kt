package com.beomjin.springeventlab.coupon.entity

import com.beomjin.springeventlab.global.exception.BusinessException
import com.beomjin.springeventlab.global.exception.ErrorCode
import com.beomjin.springeventlab.support.EventFixture
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.spec.style.DescribeSpec
import io.kotest.matchers.shouldBe

class EventTest : DescribeSpec({

    describe("Event 생성") {
        it("정상 생성 시 issuedQuantity=0, remainingQuantity=totalQuantity") {
            val event = EventFixture.event(totalQuantity = 100)
            event.issuedQuantity shouldBe 0
            event.remainingQuantity shouldBe 100
            event.eventStatus shouldBe EventStatus.READY
        }
    }

    describe("issue()") {
        context("READY 상태") {
            it("EVENT_NOT_OPEN을 던진다") {
                val event = EventFixture.event(status = EventStatus.READY)
                shouldThrow<BusinessException> { event.issue() }
                    .errorCode shouldBe ErrorCode.EVENT_NOT_OPEN
            }
        }

        context("OPEN 상태") {
            it("재고가 있으면 issuedQuantity를 1 증가시킨다") {
                val event = EventFixture.openEvent(totalQuantity = 100)
                event.issue()
                event.issuedQuantity shouldBe 1
                event.remainingQuantity shouldBe 99
            }

            it("재고가 0이면 EVENT_OUT_OF_STOCK을 던진다") {
                val event = EventFixture.openEvent(totalQuantity = 1)
                event.issue()
                shouldThrow<BusinessException> { event.issue() }
                    .errorCode shouldBe ErrorCode.EVENT_OUT_OF_STOCK
            }
        }

        context("CLOSED 상태") {
            it("EVENT_NOT_OPEN을 던진다") {
                val event = EventFixture.openEvent()
                event.close()
                shouldThrow<BusinessException> { event.issue() }
                    .errorCode shouldBe ErrorCode.EVENT_NOT_OPEN
            }
        }
    }

    describe("open()") {
        it("READY → OPEN 전이 성공") {
            val event = EventFixture.event(status = EventStatus.READY)
            event.open()
            event.eventStatus shouldBe EventStatus.OPEN
        }

        it("OPEN → OPEN 시 EVENT_INVALID_STATUS_TRANSITION") {
            val event = EventFixture.openEvent()
            shouldThrow<BusinessException> { event.open() }
                .errorCode shouldBe ErrorCode.EVENT_INVALID_STATUS_TRANSITION
        }
    }

    describe("close()") {
        it("OPEN → CLOSED 전이 성공") {
            val event = EventFixture.openEvent()
            event.close()
            event.eventStatus shouldBe EventStatus.CLOSED
        }

        it("READY → CLOSED 시 EVENT_INVALID_STATUS_TRANSITION") {
            val event = EventFixture.event(status = EventStatus.READY)
            shouldThrow<BusinessException> { event.close() }
                .errorCode shouldBe ErrorCode.EVENT_INVALID_STATUS_TRANSITION
        }
    }
})
