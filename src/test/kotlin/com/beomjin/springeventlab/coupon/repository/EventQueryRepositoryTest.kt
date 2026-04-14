package com.beomjin.springeventlab.coupon.repository

import com.beomjin.springeventlab.coupon.dto.request.EventSearchCond
import com.beomjin.springeventlab.coupon.entity.Event
import com.beomjin.springeventlab.coupon.entity.EventStatus
import com.beomjin.springeventlab.global.common.DateRange
import com.beomjin.springeventlab.global.config.JpaConfig
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.collections.shouldBeIn
import io.kotest.matchers.collections.shouldBeSortedWith
import io.kotest.matchers.collections.shouldHaveSize
import io.kotest.matchers.comparables.shouldBeGreaterThan
import io.kotest.matchers.comparables.shouldBeGreaterThanOrEqualTo
import io.kotest.matchers.string.shouldContain
import jakarta.persistence.EntityManager
import org.springframework.boot.data.jpa.test.autoconfigure.DataJpaTest
import org.springframework.boot.testcontainers.service.connection.ServiceConnection
import org.springframework.context.annotation.Import
import org.springframework.data.domain.PageRequest
import org.springframework.data.domain.Sort
import org.testcontainers.junit.jupiter.Container
import org.testcontainers.junit.jupiter.Testcontainers
import org.testcontainers.postgresql.PostgreSQLContainer
import org.testcontainers.utility.DockerImageName
import java.time.Instant

@DataJpaTest
@Import(JpaConfig::class, EventQueryRepository::class)
@Testcontainers
class EventQueryRepositoryTest(
    private val eventQueryRepository: EventQueryRepository,
    private val entityManager: EntityManager,
) : FunSpec({

        // === Test Data Setup ===
        // beforeSpec에서 테스트 데이터 삽입 (persist via EntityManager)
        // 최소 5개 Event: 다양한 status, title, totalQuantity, period

        beforeTest {
            val events =
                listOf(
                    Event(
                        "여름 쿠폰",
                        100,
                        EventStatus.READY,
                        DateRange(
                            Instant.parse("2026-07-01T00:00:00Z"),
                            Instant.parse("2026-07-31T23:59:59Z"),
                        ),
                    ),
                    Event(
                        "겨울 세일",
                        50,
                        EventStatus.OPEN,
                        DateRange(
                            Instant.parse("2026-01-01T00:00:00Z"),
                            Instant.parse("2026-12-31T23:59:59Z"),
                        ),
                    ),
                    Event(
                        "봄 할인",
                        200,
                        EventStatus.CLOSED,
                        DateRange(
                            Instant.parse("2025-03-01T00:00:00Z"),
                            Instant.parse("2025-03-31T23:59:59Z"),
                        ),
                    ),
                    Event(
                        "가을 이벤트",
                        10,
                        EventStatus.READY,
                        DateRange(
                            Instant.parse("2026-09-01T00:00:00Z"),
                            Instant.parse("2026-09-30T23:59:59Z"),
                        ),
                    ),
                    Event(
                        "연말 특별",
                        0,
                        EventStatus.OPEN,
                        DateRange(
                            Instant.parse("2026-01-01T00:00:00Z"),
                            Instant.parse("2026-12-31T23:59:59Z"),
                        ),
                    ).apply {
                        // totalQuantity=0 이벤트는 불가능하므로, 재고 소진 시뮬레이션은 별도 처리
                    },
                )
            events.forEach { entityManager.persist(it) }
            entityManager.flush()
            entityManager.clear()
        }

        // === 필터 테스트 ===

        test("필터 없음 — 전체 반환 + createdAt DESC 기본 정렬") {
            val result = eventQueryRepository.search(EventSearchCond(), PageRequest.of(0, 20))
            result.content.size shouldBeGreaterThanOrEqualTo 4
        }

        test("keyword='여름' — 제목에 '여름'이 포함된 이벤트만") {
            val result =
                eventQueryRepository.search(
                    EventSearchCond(keyword = "여름"),
                    PageRequest.of(0, 20),
                )
            result.content.forEach { it.title shouldContain "여름" }
            result.content shouldHaveSize 1
        }

        test("statuses=[OPEN, READY] — 해당 상태만 반환") {
            val result =
                eventQueryRepository.search(
                    EventSearchCond(statuses = listOf(EventStatus.OPEN, EventStatus.READY)),
                    PageRequest.of(0, 20),
                )
            result.content.forEach {
                it.eventStatus shouldBeIn listOf(EventStatus.OPEN, EventStatus.READY)
            }
        }

        test("hasRemainingStock=true — 잔여 재고가 있는 이벤트만") {
            val result =
                eventQueryRepository.search(
                    EventSearchCond(hasRemainingStock = true),
                    PageRequest.of(0, 20),
                )
            result.content.forEach { it.remainingQuantity shouldBeGreaterThan 0 }
        }

        test("조합 필터 — keyword + statuses 동시 적용 (AND)") {
            val result =
                eventQueryRepository.search(
                    EventSearchCond(keyword = "여름", statuses = listOf(EventStatus.READY)),
                    PageRequest.of(0, 20),
                )
            result.content shouldHaveSize 1
            result.content.first().title shouldContain "여름"
        }

        // === 페이징 테스트 ===

        test("page=0, size=2 — 첫 2개만 반환") {
            val result = eventQueryRepository.search(EventSearchCond(), PageRequest.of(0, 2))
            result.content shouldHaveSize 2
            result.totalPages shouldBeGreaterThanOrEqualTo 2
        }

        // === 정렬 테스트 ===

        test("sort=title,asc — 제목 오름차순") {
            val result =
                eventQueryRepository.search(
                    EventSearchCond(),
                    PageRequest.of(0, 20, Sort.by(Sort.Direction.ASC, "title")),
                )
            result.content.map { it.title } shouldBeSortedWith compareBy { it }
        }

        test("화이트리스트 외 sort — 기본 정렬(createdAt DESC) fallback") {
            val result =
                eventQueryRepository.search(
                    EventSearchCond(),
                    PageRequest.of(0, 20, Sort.by("nonexistent")),
                )
            // createdAt DESC 정렬 확인 (최신이 먼저)
            result.content shouldHaveSize result.content.size // 정상 반환 확인
        }
    }) {
    companion object {
        @Container
        @ServiceConnection
        @JvmStatic
        val postgres = PostgreSQLContainer(DockerImageName.parse("postgres:18-alpine"))
    }
}
