package com.beomjin.springeventlab.coupon.entity

import com.beomjin.springeventlab.global.exception.BusinessException
import com.beomjin.springeventlab.global.exception.ErrorCode
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.spec.style.DescribeSpec
import io.kotest.datatest.withData
import io.kotest.matchers.shouldBe

class EventStatusTest :
    DescribeSpec({

        describe("isIssuable") {
            withData(
                nameFn = { (status, _) -> "$status.isIssuable" },
                EventStatus.READY to false,
                EventStatus.OPEN to true,
                EventStatus.CLOSED to false,
            ) { (status, expected) ->
                status.isIssuable shouldBe expected
            }
        }

        describe("canTransitionTo") {
            withData(
                nameFn = { (from, to, _) -> "$from → $to" },
                Triple(EventStatus.READY, EventStatus.OPEN, true),
                Triple(EventStatus.READY, EventStatus.CLOSED, false),
                Triple(EventStatus.OPEN, EventStatus.CLOSED, true),
                Triple(EventStatus.OPEN, EventStatus.READY, false),
                Triple(EventStatus.CLOSED, EventStatus.READY, false),
                Triple(EventStatus.CLOSED, EventStatus.OPEN, false),
            ) { (from, to, expected) ->
                from.canTransitionTo(to) shouldBe expected
            }
        }

        describe("transitionTo") {
            it("READY → OPEN 성공") {
                EventStatus.READY.transitionTo(EventStatus.OPEN) shouldBe EventStatus.OPEN
            }

            it("OPEN → CLOSED 성공") {
                EventStatus.OPEN.transitionTo(EventStatus.CLOSED) shouldBe EventStatus.CLOSED
            }

            context("허용되지 않은 전이") {
                withData(
                    nameFn = { (from, to) -> "$from → $to" },
                    EventStatus.READY to EventStatus.CLOSED,
                    EventStatus.OPEN to EventStatus.READY,
                    EventStatus.CLOSED to EventStatus.READY,
                    EventStatus.CLOSED to EventStatus.OPEN,
                ) { (from, to) ->
                    shouldThrow<BusinessException> { from.transitionTo(to) }
                        .errorCode shouldBe ErrorCode.EVENT_INVALID_STATUS_TRANSITION
                }
            }
        }
    })
