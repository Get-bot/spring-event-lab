package com.beomjin.springeventlab.coupon.entity

import com.beomjin.springeventlab.global.common.DateRange
import com.beomjin.springeventlab.global.exception.BusinessException
import com.beomjin.springeventlab.global.exception.ErrorCode
import com.beomjin.springeventlab.support.EventFixture
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.spec.style.DescribeSpec
import io.kotest.datatest.withData
import io.kotest.matchers.shouldBe


class DateRangeTest :
    DescribeSpec({

        describe("DateRange 생성") {

            it("startedAt < endedAt이면 정상 생성된다") {
                val range = DateRange(EventFixture.DEFAULT_START, EventFixture.DEFAULT_END)

                range.startedAt shouldBe EventFixture.DEFAULT_START
                range.endedAt shouldBe EventFixture.DEFAULT_END
            }
        }

        context("불변식 위반 (startedAt >= endedAt)") {
            withData(
                nameFn = { (s, e) -> "start=$s, end=$e" },
                // startedAt > endedAt (역순)
                EventFixture.DEFAULT_END to EventFixture.DEFAULT_START,
                // startedAt == endedAt (동일)
                EventFixture.DEFAULT_START to EventFixture.DEFAULT_START,
            ) { (start, end) ->
                val ex = shouldThrow<BusinessException> { DateRange(start, end) }
                ex.errorCode shouldBe ErrorCode.INVALID_DATE_RANGE
            }
        }

        describe("contains — 반개구간 [start, end)") {
            val range = EventFixture.dateRange()

            withData(
                nameFn = { (instant, expected) -> "contains($instant) = $expected" },
                range.startedAt to true, // start 포함
                range.endedAt to false, // end 제외
                range.startedAt.minusMillis(1) to false, // start 직전 제외
                range.startedAt.plusSeconds(3600) to true, // 중간값 포함
            ) { (instant, expected) ->
                range.contains(instant) shouldBe expected
            }
        }

        describe("시간 상태 판단") {
            val range = EventFixture.dateRange()

            context("isUpcoming") {
                it("start 이전이면 true") {
                    range.isUpcoming(range.startedAt.minusSeconds(1)) shouldBe true
                }
                it("start 시점이면 false") {
                    range.isUpcoming(range.startedAt) shouldBe false
                }
            }

            context("isOngoing") {
                it("start 시점이면 true") {
                    range.isOngoing(range.startedAt) shouldBe true
                }
                it("end 직전이면 true") {
                    range.isOngoing(range.endedAt.minusMillis(1)) shouldBe true
                }
                it("end 시점이면 false") {
                    range.isOngoing(range.endedAt) shouldBe false
                }
            }

            context("isEnded") {
                it("end 시점이면 true") {
                    range.isEnded(range.endedAt) shouldBe true
                }
                it("end 이후면 true") {
                    range.isEnded(range.endedAt.plusSeconds(1)) shouldBe true
                }
                it("end 직전이면 false") {
                    range.isEnded(range.endedAt.minusMillis(1)) shouldBe false
                }
            }
        }
    })
