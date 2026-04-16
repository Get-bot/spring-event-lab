package com.beomjin.springeventlab.coupon

import com.beomjin.springeventlab.coupon.repository.CouponIssueRepository
import com.beomjin.springeventlab.coupon.repository.EventRepository
import com.beomjin.springeventlab.coupon.service.CouponIssueService
import com.beomjin.springeventlab.global.common.DateRange
import com.beomjin.springeventlab.global.exception.BusinessException
import com.beomjin.springeventlab.global.exception.ErrorCode
import com.beomjin.springeventlab.support.EventFixture
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.boot.testcontainers.service.connection.ServiceConnection
import org.springframework.data.redis.core.StringRedisTemplate
import org.springframework.test.context.ActiveProfiles
import org.testcontainers.containers.GenericContainer
import org.testcontainers.kafka.KafkaContainer
import org.testcontainers.postgresql.PostgreSQLContainer
import org.testcontainers.utility.DockerImageName
import java.time.Instant
import java.util.UUID
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

@SpringBootTest
@ActiveProfiles("test")
class CouponIssueConcurrencyTest(
    private val couponIssueService: CouponIssueService,
    private val eventRepository: EventRepository,
    private val couponIssueRepository: CouponIssueRepository,
    private val redisTemplate: StringRedisTemplate,
) : FunSpec({

    // --- Helpers ---

    fun createOpenEvent(totalQuantity: Int) =
        eventRepository.save(
            EventFixture.openEvent(
                totalQuantity = totalQuantity,
                period = DateRange(
                    startedAt = Instant.now().minusSeconds(3600),
                    endedAt = Instant.now().plusSeconds(3600),
                ),
            ),
        )

    fun concurrentExecute(
        taskCount: Int,
        poolSize: Int = minOf(taskCount, 200),
        action: (index: Int) -> Unit,
    ) {
        val executor = Executors.newFixedThreadPool(poolSize)
        val startLatch = CountDownLatch(1)
        val doneLatch = CountDownLatch(taskCount)

        repeat(taskCount) { i ->
            executor.submit {
                startLatch.await()
                try {
                    action(i)
                } finally {
                    doneLatch.countDown()
                }
            }
        }

        startLatch.countDown()
        try {
            val completed = doneLatch.await(120, TimeUnit.SECONDS)
            check(completed) { "concurrentExecute timed out: $taskCount tasks / $poolSize threads" }
        } finally {
            executor.shutdownNow()
            executor.awaitTermination(10, TimeUnit.SECONDS)
        }
    }

    // --- Lifecycle ---

    beforeTest {
        couponIssueRepository.deleteAllInBatch()
        redisTemplate.execute { connection ->
            connection.serverCommands().flushAll()
            null
        }
    }

    // --- Test Cases ---

    test("TC-01: 1,000개 쿠폰에 3,000건 동시 요청 시 정확히 1,000건만 발급된다") {
        // Given — Testcontainers + HikariCP(10) 환경에서 안정적 완료를 위해 3x 과부하
        val totalQuantity = 1_000
        val taskCount = 3_000
        val event = createOpenEvent(totalQuantity)
        val successCount = AtomicInteger(0)
        val soldOutCount = AtomicInteger(0)

        // When
        concurrentExecute(taskCount) { _ ->
            try {
                couponIssueService.issue(event.id, UUID.randomUUID())
                successCount.incrementAndGet()
            } catch (e: BusinessException) {
                when (e.errorCode) {
                    ErrorCode.EVENT_SOLD_OUT -> soldOutCount.incrementAndGet()
                    else -> throw e
                }
            }
        }

        // Then — 3중 검증
        successCount.get() shouldBe totalQuantity
        soldOutCount.get() shouldBe (taskCount - totalQuantity)
        couponIssueRepository.count() shouldBe totalQuantity.toLong()
    }

    test("TC-02: 동일 userId로 100건 동시 요청 시 1건만 발급된다") {
        // Given
        val event = createOpenEvent(totalQuantity = 100)
        val sameUserId = UUID.randomUUID()
        val successCount = AtomicInteger(0)
        val duplicateCount = AtomicInteger(0)

        // When
        concurrentExecute(taskCount = 100) { _ ->
            try {
                couponIssueService.issue(event.id, sameUserId)
                successCount.incrementAndGet()
            } catch (e: BusinessException) {
                when (e.errorCode) {
                    ErrorCode.COUPON_ALREADY_ISSUED -> duplicateCount.incrementAndGet()
                    else -> throw e
                }
            }
        }

        // Then
        successCount.get() shouldBe 1
        duplicateCount.get() shouldBe 99
        couponIssueRepository.count() shouldBe 1
    }

    test("TC-03: 이미 매진된 이벤트에 1,000건 요청 시 전부 매진 응답") {
        // Given — 수량 5인 이벤트를 순차 발급으로 매진
        val event = createOpenEvent(totalQuantity = 5)
        repeat(5) {
            couponIssueService.issue(event.id, UUID.randomUUID())
        }
        val preIssuedCount = couponIssueRepository.count()
        val soldOutCount = AtomicInteger(0)

        // When — 매진 상태에서 1,000건 동시 요청
        concurrentExecute(taskCount = 1_000) { _ ->
            try {
                couponIssueService.issue(event.id, UUID.randomUUID())
            } catch (e: BusinessException) {
                when (e.errorCode) {
                    ErrorCode.EVENT_SOLD_OUT -> soldOutCount.incrementAndGet()
                    else -> throw e
                }
            }
        }

        // Then
        soldOutCount.get() shouldBe 1_000
        couponIssueRepository.count() shouldBe preIssuedCount
    }

    test("TC-04: 발급 후 Redis issued Set 크기 == DB 발급 건수") {
        // Given
        val totalQuantity = 500
        val event = createOpenEvent(totalQuantity)

        // When — 1,000건 요청 (500건 성공 예상)
        concurrentExecute(taskCount = 1_000) { _ ->
            try {
                couponIssueService.issue(event.id, UUID.randomUUID())
            } catch (_: BusinessException) {
                // SOLD_OUT 예상 — 무시
            }
        }

        // Then — Redis와 DB 양쪽 정합성 검증
        val dbCount = couponIssueRepository.count()
        val redisIssuedSize = redisTemplate.opsForSet()
            .size("coupon:issued:{${event.id}}") ?: 0

        dbCount shouldBe totalQuantity.toLong()
        redisIssuedSize shouldBe dbCount
    }
}) {
    companion object {
        @ServiceConnection
        val postgres =
            PostgreSQLContainer(DockerImageName.parse("postgres:18-alpine"))
                .apply { start() }

        @ServiceConnection(name = "redis")
        val redis: GenericContainer<*> =
            GenericContainer(DockerImageName.parse("redis:7-alpine"))
                .withExposedPorts(6379)
                .apply { start() }

        @ServiceConnection
        val kafka =
            KafkaContainer(DockerImageName.parse("apache/kafka-native:latest"))
                .apply { start() }
    }
}
