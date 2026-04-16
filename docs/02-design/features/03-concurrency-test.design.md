# Concurrency Test Design Document

> **Summary**: Kotest 6.1.0 FunSpec + ExecutorService + CountDownLatch로 선착순 발급의 동시성 안전성을 증명하는 통합 테스트 상세 설계
>
> **Project**: spring-event-lab
> **Version**: 0.0.1-SNAPSHOT
> **Author**: beomjin
> **Date**: 2026-04-15
> **Status**: Draft
> **Planning Doc**: [03-concurrency-test.plan.md](../../01-plan/features/03-concurrency-test.plan.md)
> **Depends On**: 02-redis-stock (CouponIssueService, RedisStockRepository, issue_coupon.lua)

---

## Executive Summary

| Perspective | Content |
|-------------|---------|
| **Problem** | Lua 스크립트의 원자성은 코드 리뷰로 확인했지만, 실제 동시 요청 환경에서 초과/중복 발급이 0건인지 코드만으로는 증명 불가능하다 |
| **Solution** | `startLatch(1)` 동시 출발 패턴으로 10,000건 요청을 진짜 동시에 발사하고, DB/Redis 양쪽에서 발급 건수 정합성을 검증하는 통합 테스트를 작성한다 |
| **Function/UX Effect** | `./gradlew test --tests *ConcurrencyTest` 한 줄로 "1,000개 쿠폰에 10,000건 → 정확히 1,000건" 증명 |
| **Core Value** | 동시성 버그는 테스트로만 잡을 수 있다는 원칙을 실제 코드로 체득하고, 이중 래치 패턴과 Testcontainers 기반 재현 가능한 동시성 테스트 작성법을 학습한다 |

---

## 1. Overview

### 1.1 Design Goals

- **재현 가능성**: Testcontainers로 실제 Redis + PostgreSQL을 사용하여 CI에서도 동일 결과 보장
- **진짜 동시성**: `startLatch` 패턴으로 모든 스레드가 준비된 후 동시에 출발 (스레드 생성 시간 차이 제거)
- **Service 레벨 테스트**: HTTP 계층 없이 `CouponIssueService.issue()` 직접 호출로 순수 비즈니스 로직 검증
- **이중 정합성**: DB 발급 건수와 Redis issued Set 크기가 일치하는지 양방향 검증
- **기존 패턴 준수**: event-crud-test에서 확립된 Kotest FunSpec + companion container 패턴 재사용

### 1.2 Design Principles

- **Kotest 6.1.0 일관성**: `FunSpec`, `shouldBe`, `beforeTest` 사용 (JUnit 5 스타일 혼용 금지)
- **Fixture 재사용**: `EventFixture.openEvent()` 확장으로 "현재 시간이 포함되는" 이벤트 생성
- **테스트 격리**: `beforeTest`에서 DB + Redis 데이터 초기화 (테스트 간 상태 오염 방지)
- **타임아웃 설정**: 동시성 테스트 데드락 방지를 위한 `timeout` 명시

---

## 2. Architecture

### 2.1 Test Class Diagram

```
┌──────────────────────────────────────────────────────────┐
│  CouponIssueConcurrencyTest : FunSpec                    │
│  @SpringBootTest @ActiveProfiles("test")                 │
│                                                          │
│  ┌─────────────┐  ┌──────────────────┐  ┌────────────┐  │
│  │ CouponIssue │  │ RedisStock       │  │ Event      │  │
│  │ Service     │  │ Repository       │  │ Repository │  │
│  │ (inject)    │  │ (Redis 검증용)   │  │ (inject)   │  │
│  └──────┬──────┘  └────────┬─────────┘  └─────┬──────┘  │
│         │                  │                   │         │
│  ┌──────▼──────────────────▼───────────────────▼──────┐  │
│  │              Testcontainers (companion)             │  │
│  │  PostgreSQL 18-alpine  +  Redis 7-alpine  + Kafka  │  │
│  └────────────────────────────────────────────────────┘  │
│                                                          │
│  test("TC-01: 초과 발급 검증")                           │
│  test("TC-02: 중복 발급 검증")                           │
│  test("TC-03: 매진 후 요청 검증")                        │
│  test("TC-04: Redis-DB 정합성 검증")                     │
└──────────────────────────────────────────────────────────┘
```

### 2.2 Dependencies

| 컴포넌트 | 의존 대상 | 용도 |
|----------|----------|------|
| 테스트 클래스 | `CouponIssueService` | 발급 메서드 직접 호출 |
| 테스트 클래스 | `EventRepository` | 이벤트 생성 (DB 저장) |
| 테스트 클래스 | `CouponIssueRepository` | 발급 건수 확인 (DB 조회) |
| 테스트 클래스 | `StringRedisTemplate` | Redis issued Set 크기 확인, 키 정리 |
| 테스트 클래스 | `EventFixture` | 테스트 이벤트 객체 생성 |
| Testcontainers | PostgreSQL 18-alpine | DB 환경 |
| Testcontainers | Redis 7-alpine | Redis 환경 |
| Testcontainers | Kafka (apache/kafka-native) | 앱 부팅 의존성 충족 |

---

## 3. Detailed Design

### 3.1 테스트 클래스 구조

```kotlin
package com.beomjin.springeventlab.coupon

@SpringBootTest
@ActiveProfiles("test")
class CouponIssueConcurrencyTest(
    private val couponIssueService: CouponIssueService,
    private val eventRepository: EventRepository,
    private val couponIssueRepository: CouponIssueRepository,
    private val redisTemplate: StringRedisTemplate,
) : FunSpec({

    beforeTest {
        couponIssueRepository.deleteAllInBatch()
        redisTemplate.execute { connection ->
            connection.serverCommands().flushAll()
            null
        }
    }

    // TC-01 ~ TC-04
}) {
    companion object {
        @ServiceConnection
        @JvmStatic
        val postgres = PostgreSQLContainer(DockerImageName.parse("postgres:18-alpine"))
            .apply { start() }

        @ServiceConnection(name = "redis")
        @JvmStatic
        val redis: GenericContainer<*> =
            GenericContainer(DockerImageName.parse("redis:7-alpine"))
                .withExposedPorts(6379)
                .apply { start() }

        @ServiceConnection
        @JvmStatic
        val kafka = KafkaContainer(DockerImageName.parse("apache/kafka-native:latest"))
            .apply { start() }
    }
}
```

**설계 결정 — companion container vs IntegrationTestBase**:

`EventCrudIntegrationTest`에서 확인된 패턴을 그대로 따른다. `IntegrationTestBase` 상속 대신 companion object에서 컨테이너를 직접 선언하는 이유:
- Kotest `FunSpec` 생성자 파라미터 주입과 abstract class 상속을 동시에 사용할 때 Spring Context 초기화 순서 문제 방지
- 테스트 클래스마다 독립적인 컨테이너 생명주기 관리 가능
- `@JvmStatic` + `@ServiceConnection`이 companion에서만 정상 동작 (Kotest + Spring Boot 4 호환성)

### 3.2 이벤트 생성 헬퍼

```kotlin
// 테스트 내 private 함수로 정의
private fun createOpenEvent(totalQuantity: Int): Event {
    val now = Instant.now()
    return eventRepository.save(
        Event(
            title = "동시성 테스트 이벤트",
            totalQuantity = totalQuantity,
            status = EventStatus.READY,
            period = DateRange(
                startedAt = now.minusSeconds(3600),  // 1시간 전
                endedAt = now.plusSeconds(3600),       // 1시간 후
            ),
        ).also { it.open() }
    )
}
```

**설계 결정**: `EventFixture.openEvent()`는 `DEFAULT_START`(2026-07-01)를 사용하므로 `period.contains(Instant.now())`가 실패할 수 있다. 동시성 테스트에서는 **현재 시간을 포함하는 period**가 필수이므로, 테스트 내에서 직접 생성한다.

### 3.3 이중 래치 동시성 패턴

```kotlin
/**
 * N개 스레드가 '동시에' action을 실행하고, 모든 완료를 기다린다.
 *
 * startLatch: 모든 스레드가 준비될 때까지 대기 → countDown으로 동시 출발
 * doneLatch: 모든 스레드 완료를 대기
 */
private fun concurrentExecute(
    threadCount: Int,
    action: (index: Int) -> Unit,
) {
    val executor = Executors.newFixedThreadPool(threadCount)
    val startLatch = CountDownLatch(1)
    val doneLatch = CountDownLatch(threadCount)

    repeat(threadCount) { i ->
        executor.submit {
            startLatch.await()
            try {
                action(i)
            } finally {
                doneLatch.countDown()
            }
        }
    }

    startLatch.countDown()  // 동시 출발
    doneLatch.await(30, TimeUnit.SECONDS)  // 최대 30초 대기
    executor.shutdown()
}
```

**설계 결정 — `concurrentExecute` 헬퍼 추출**:

TC-01 ~ TC-03 모두 동일한 "N개 스레드 동시 실행" 패턴을 사용하므로, 헬퍼 함수로 추출하여 중복 제거. `action` 람다에 `index`를 전달하여 스레드마다 다른 userId를 사용할 수 있도록 한다.

### 3.4 TC-01: 초과 발급 검증

```kotlin
test("TC-01: 1,000개 쿠폰에 10,000건 동시 요청 시 정확히 1,000건만 발급된다") {
    // Given
    val totalQuantity = 1_000
    val requestCount = 10_000
    val event = createOpenEvent(totalQuantity)
    val successCount = AtomicInteger(0)
    val soldOutCount = AtomicInteger(0)

    // When
    concurrentExecute(requestCount) { i ->
        try {
            couponIssueService.issue(event.id, UUID.randomUUID())
            successCount.incrementAndGet()
        } catch (e: BusinessException) {
            if (e.errorCode == ErrorCode.EVENT_SOLD_OUT) {
                soldOutCount.incrementAndGet()
            }
        }
    }

    // Then
    successCount.get() shouldBe totalQuantity
    soldOutCount.get() shouldBe (requestCount - totalQuantity)
    couponIssueRepository.count() shouldBe totalQuantity.toLong()
}
```

**설계 결정**:
- `AtomicInteger`로 성공/매진 건수를 스레드 안전하게 카운트
- `UUID.randomUUID()`로 각 요청마다 다른 userId (중복 발급이 아닌 초과 발급 검증)
- DB 검증(`couponIssueRepository.count()`)과 카운터 검증(`successCount`) 이중 확인
- `requestCount`를 10,000으로 설정: 스레드 풀 크기는 `newFixedThreadPool(requestCount)`이지만, OS 스레드 제한이 우려될 경우 200~500 수준으로 조정 가능

### 3.5 TC-02: 중복 발급 검증

```kotlin
test("TC-02: 동일 userId로 100 동시 요청 시 1건만 발급된다") {
    // Given
    val event = createOpenEvent(totalQuantity = 100)
    val sameUserId = UUID.randomUUID()
    val successCount = AtomicInteger(0)
    val duplicateCount = AtomicInteger(0)

    // When
    concurrentExecute(100) {
        try {
            couponIssueService.issue(event.id, sameUserId)
            successCount.incrementAndGet()
        } catch (e: BusinessException) {
            if (e.errorCode == ErrorCode.COUPON_ALREADY_ISSUED) {
                duplicateCount.incrementAndGet()
            }
        }
    }

    // Then
    successCount.get() shouldBe 1
    duplicateCount.get() shouldBe 99
    couponIssueRepository.count() shouldBe 1
}
```

### 3.6 TC-03: 매진 후 요청 검증

```kotlin
test("TC-03: 이미 매진된 이벤트에 1,000건 요청 시 전부 매진 응답") {
    // Given — 수량 0인 이벤트
    val event = createOpenEvent(totalQuantity = 0)
    val soldOutCount = AtomicInteger(0)

    // When
    concurrentExecute(1_000) {
        try {
            couponIssueService.issue(event.id, UUID.randomUUID())
        } catch (e: BusinessException) {
            if (e.errorCode == ErrorCode.EVENT_SOLD_OUT) {
                soldOutCount.incrementAndGet()
            }
        }
    }

    // Then
    soldOutCount.get() shouldBe 1_000
    couponIssueRepository.count() shouldBe 0
}
```

**설계 결정**: `totalQuantity = 0`으로 이벤트를 생성하면, Lua 스크립트의 `GET` → `stock == 0` 경로로 즉시 매진 응답. 1,000건 모두 쓰기 0회 경로를 타므로 매우 빠르게 완료될 것으로 예상.

### 3.7 TC-04: Redis-DB 정합성 검증

```kotlin
test("TC-04: 발급 후 Redis issued Set 크기 == DB 발급 건수") {
    // Given
    val totalQuantity = 500
    val event = createOpenEvent(totalQuantity)

    // When — 1,000건 요청 (500건 성공 예상)
    concurrentExecute(1_000) {
        try {
            couponIssueService.issue(event.id, UUID.randomUUID())
        } catch (_: BusinessException) {}
    }

    // Then — Redis와 DB 양쪽 검증
    val dbCount = couponIssueRepository.count()
    val redisIssuedSize = redisTemplate.opsForSet()
        .size("coupon:issued:{${event.id}}") ?: 0

    dbCount shouldBe totalQuantity.toLong()
    redisIssuedSize shouldBe dbCount
}
```

---

## 4. Cleanup Strategy

### 4.1 beforeTest 초기화

```kotlin
beforeTest {
    // 1. DB 초기화 — CouponIssue 테이블 비우기
    couponIssueRepository.deleteAllInBatch()

    // 2. Redis 초기화 — 모든 키 삭제
    redisTemplate.execute { connection ->
        connection.serverCommands().flushAll()
        null
    }
}
```

**설계 결정 — `flushAll` 사용 이유**:

동시성 테스트는 Redis 키를 예측 불가능한 타이밍에 생성한다. 특정 패턴(`coupon:stock:*`, `coupon:issued:*`)으로 삭제하는 것보다 `flushAll`이 확실하다. Testcontainers Redis는 테스트 전용이므로 안전.

### 4.2 Event 데이터 처리

- Event는 `beforeTest`에서 삭제하지 **않는다**
- 각 테스트에서 `createOpenEvent()`로 새 Event를 생성하므로, UUID 기반 독립성 보장
- `coupon:stock:{eventId}` 키도 `flushAll`로 이미 정리됨

---

## 5. Thread Pool Sizing

| TC | 스레드 수 | 근거 |
|----|:---------:|------|
| TC-01 | 10,000 (또는 pool=200, repeat=50) | 가장 높은 동시성 부하 |
| TC-02 | 100 | 동일 userId 중복 검증에 충분 |
| TC-03 | 1,000 | 매진 경로 검증 |
| TC-04 | 1,000 | Redis-DB 정합성 검증 |

**OS 스레드 제한 고려**:

`newFixedThreadPool(10_000)`은 OS에 따라 스레드 생성 실패할 수 있다. 실제 구현 시 **풀 크기 200 + repeat 50**으로 분할 요청하는 방식도 고려:

```kotlin
// 대안: 스레드 풀 200개로 10,000건 처리
val poolSize = 200
val executor = Executors.newFixedThreadPool(poolSize)
val startLatch = CountDownLatch(1)
val doneLatch = CountDownLatch(10_000)

repeat(10_000) { i ->
    executor.submit {
        startLatch.await()
        try { action(i) } finally { doneLatch.countDown() }
    }
}
```

이 방식은 200개 스레드가 10,000건 작업을 큐에서 꺼내 처리한다. 동시 실행 수는 200개로 제한되지만, 실전 환경과 더 유사하다.

---

## 6. Timeout & Stability

### 6.1 테스트 타임아웃

```kotlin
// Kotest config — 개별 테스트 타임아웃
test("TC-01: ...").config(timeout = 60.seconds) {
    // ...
}
```

| TC | 타임아웃 | 근거 |
|----|:--------:|------|
| TC-01 | 60s | 10,000건 처리 + DB 저장 |
| TC-02 | 30s | 100건, 빠르게 완료 |
| TC-03 | 30s | 읽기만, 매우 빠름 |
| TC-04 | 60s | 1,000건 처리 |

### 6.2 데드락 방지

- `doneLatch.await(timeout, TimeUnit.SECONDS)` 사용으로 무한 대기 방지
- `executor.shutdown()` + `executor.awaitTermination()` 호출로 스레드 풀 정리
- `catch` 블록에서 `BusinessException`만 무시, 그 외 예외는 전파

---

## 7. File Structure

```
src/test/kotlin/com/beomjin/springeventlab/
├── coupon/
│   ├── EventCrudIntegrationTest.kt         ← EXISTING (L4)
│   └── CouponIssueConcurrencyTest.kt       ← NEW (L4 동시성)
├── support/
│   ├── IntegrationTestBase.kt              ← EXISTING
│   └── EventFixture.kt                    ← EXISTING
└── io/kotest/provided/
    └── ProjectConfig.kt                   ← EXISTING
```

---

## 8. Implementation Order

| Step | Description | Depends On |
|------|-------------|------------|
| 1 | `CouponIssueConcurrencyTest.kt` 생성 — 클래스 골격 + companion containers | — |
| 2 | `beforeTest` 초기화 로직 (DB + Redis flushAll) | Step 1 |
| 3 | `concurrentExecute` 헬퍼 함수 | Step 1 |
| 4 | `createOpenEvent` 헬퍼 함수 | Step 1 |
| 5 | TC-01 초과 발급 검증 | Step 2, 3, 4 |
| 6 | TC-02 중복 발급 검증 | Step 2, 3, 4 |
| 7 | TC-03 매진 후 요청 검증 | Step 2, 3, 4 |
| 8 | TC-04 Redis-DB 정합성 검증 | Step 2, 3, 4 |
| 9 | 테스트 실행 + 스레드 풀 크기 조정 | Step 5-8 |

---

## 9. Success Criteria

- [ ] TC-01: 1,000개 쿠폰, 10,000건 동시 요청 → 정확히 1,000건 발급 (초과 0건)
- [ ] TC-02: 동일 userId 100건 동시 → 1건만 발급 (중복 0건)
- [ ] TC-03: 매진 이벤트 1,000건 → 0건 발급
- [ ] TC-04: Redis issued Set == DB 발급 건수
- [ ] 모든 테스트 60초 이내 완료
- [ ] CI 환경(Testcontainers)에서 재현 가능
- [ ] `./gradlew test --tests *ConcurrencyTest` 단독 실행 가능

---

## Version History

| Version | Date | Changes | Author |
|---------|------|---------|--------|
| 0.1 | 2026-04-15 | Initial design — 4 TC, companion container 패턴, concurrentExecute 헬퍼, Redis flushAll 전략 | beomjin |
| 0.2 | 2026-04-16 | redis-stock 구현 반영: Redis 키 hash tag `{$eventId}` 패턴 적용 | beomjin |
