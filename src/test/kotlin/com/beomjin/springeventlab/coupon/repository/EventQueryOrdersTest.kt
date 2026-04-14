package com.beomjin.springeventlab.coupon.repository

import com.querydsl.core.types.Order
import io.kotest.core.spec.style.DescribeSpec
import io.kotest.datatest.withData
import io.kotest.matchers.collections.shouldHaveSize
import io.kotest.matchers.shouldBe
import org.springframework.data.domain.Sort

class EventQueryOrdersTest :
    DescribeSpec({

        describe("orders(sort)") {

            it("Sort.unsorted()이면 createdAt DESC fallback") {
                val orders = EventQuery.orders(Sort.unsorted())
                orders shouldHaveSize 1
                orders[0].order shouldBe Order.DESC
            }

            context("화이트리스트 정렬 필드") {
                withData("title", "createdAt", "startedAt", "endedAt", "totalQuantity") { field ->
                    val orders = EventQuery.orders(Sort.by(Sort.Direction.ASC, field))
                    orders shouldHaveSize 1
                    orders[0].order shouldBe Order.ASC
                }
            }

            it("다중 정렬 — title,asc + createdAt,desc") {
                val sort =
                    Sort.by(
                        Sort.Order.asc("title"),
                        Sort.Order.desc("createdAt"),
                    )
                val orders = EventQuery.orders(sort)
                orders shouldHaveSize 2
            }

            context("화이트리스트 외 필드") {
                it("무효 필드만이면 fallback") {
                    val orders = EventQuery.orders(Sort.by("password"))
                    orders shouldHaveSize 1 // createdAt DESC fallback
                }

                it("유효 + 무효 혼합이면 유효한 것만") {
                    val sort =
                        Sort.by(
                            Sort.Order.asc("title"),
                            Sort.Order.asc("password"),
                        )
                    val orders = EventQuery.orders(sort)
                    orders shouldHaveSize 1 // title만
                }
            }
        }
    })
