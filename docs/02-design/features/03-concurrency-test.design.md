# Concurrency Test Design Document

> **Summary**: Kotest 6.1.0 FunSpec + ExecutorService + CountDownLatch로 선착순 발급의 동시성 안전성을 증명하는 통합 테스트 상세 설계
>
> **Project**: spring-event-lab
> **Version**: 0.0.1-SNAPSHOT
> **Author**: beomjin
> **Date**: 2026-04-16
> **Status**: Draft (v0.3)
> **Planning Doc**: [03-concurrency-test.plan.md](../../01-plan/features/03-concurrency-test.plan.md)
> **Depends On**: 02-redis-stock (CouponIssueService, RedisStockRepository, issue_coupon.lua)

---

## Executive Summary

| Perspective | Content |
|-------------|---------|
| **Problem** | Lua 스크립트의 원자성은 코드 리뷰로 확인했지만, 실제 동시 요청 환경에서 초과/중복 발급이 0건인지 코드만으로는 증명 불가능하다 |
| **Solution** | `startLatch(1)` 동시 출발 + `doneLatch(N)` 완료 대기 이중 래치 패턴으로 10,000건 요청을 동시에 발사하고, DB/Redis 양쪽에서 발급 건수 정합성을 검증하는 통합 테스트를 작성한다 |
| **Function/UX Effect** | `./gradlew test --tests *ConcurrencyTest` 한 줄로 "1,000개 쿠폰에 10,000건 → 정확히 1,000건" 증명 |
| **Core Value** | Redis Lua 원자적 재고 관리의 정합성을 동시성 테스트로 객관 검증하고, 이중 래치 패턴 + Testcontainers 기반 재현 가능한 동시성 테스트 작성법을 학습한다 |

---

## 1. Overview

### 1.1 Design Goals

- **재현 가능성**: Testcontainers로 실제 Redis + PostgreSQL을 사용하여 CI에서도 동일 결과
- **진짜 동시성**: `startLatch` 패턴으로 모든 스레드가 준비된 후 동시에 출발 (스레드 생성 시간 차이 제거)
- **Service 레벨 테스트**: HTTP 계층 없이 `CouponIssueService.issue()` 직접 호출로 순수 비즈니스 로직 검증
- **이중 정합성**: DB 발급 건수와 Redis issued Set 크기가 일치하는지 양방향 검증
- **기존 패턴 준수**: Kotest FunSpec + Testcontainers companion container 패턴 재사용

### 1.2 Design Principles

- **Kotest 6.1.0 일관성**: `FunSpec`, `shouldBe`, `beforeTest` 사용 (JUnit 5 스타일 혼용 금지)
- **Fixture 활용**: `EventFixture.openEvent()` + 커스텀 period로 "현재 시간 포함" 이벤트 생성
- **테스트 격리**: `beforeTest`에서 DB + Redis 데이터 전체 초기화 (테스트 간 상태 오염 방지)
- **스레드 풀 분리**: `poolSize`와 `taskCount`를 분리하여 OS 스레드 제한 안에서 대량 작업 실행

### 1.3 Key Architectural Insight — Redis 경로에서 Event.issue() 미호출

`CouponIssueService.issue()` 실행 흐름에서 `Event.issue()` (`issuedQuantity++`)는 **호출되지 않는다**. Redis Lua 스크립트가 재고를 원자적으로 관리하고, DB에는 `CouponIssue` 레코드만 저장한다. 따라서 발급 건수의 "source of truth"는 `coupon_issue` 테이블의 row count이며, `Event.issuedQuantity`는 Redis 경로에서 업데이트되지 않는다.

이것이 **Aggregate 분리**의 결과다: Event Aggregate와 CouponIssue Aggregate는 독립적이며, 선착순 환경에서는 Redis가 재고를 관리하고 DB는 발급 이력만 기록한다.

---

## 2. Architecture

### 2.1 Test Class Diagram

```
┌──────────────────────────────────────────────────────────┐
│  CouponIssueConcurrencyTest : FunSpec                    │
│  @SpringBootTest @ActiveProfiles("test")                 │
│                                                          │
│  Constructor-injected:                                   │
│  ┌─────────────┐  ┌──────────────────┐  ┌────────────┐  │
│  │ CouponIssue │  │ StringRedis      │  │ Event      │  │
│  │ Service     │  │ Template         │  │ Repository │  │
│  │             │  │ (Redis 검증용)   │  │            │  │
│  └──────┬──────┘  └────────┬─────────┘  └─────┬──────┘  │
│         │                  │                   │         │
│  ┌──────▼──────────────────▼───────────────────▼──────┐  │
│  │              companion object containers            │  │
│  │  PostgreSQL 18-alpine  +  Redis 7-alpine  + Kafka  │  │
│  └────────────────────────────────────────────────────┘  │
│                                                          │
│  Helpers:                                                │
│  ├── createOpenEvent(totalQuantity) → Event              │
│  └── concurrentExecute(taskCount, poolSize, action)      │
│                                                          │
│  Tests:                                                  │
│  ├── test("TC-01: 초과 발급 검증")                       │
│  ├── test("TC-02: 중복 발급 검증")                       │
│  ├── test("TC-03: 매진 후 요청 검증")                    │
│  └── test("TC-04: Redis-DB 정합성 검증")                 │
└──────────────────────────────────────────────────────────┘
```

### 2.2 CouponIssueService.issue() 실행 흐름 (테스트 대상)

```
Thread → CouponIssueService.issue(eventId, userId)
  ├── 1. eventRepository.findById(eventId)          ← DB Read
  ├── 2. event.period.contains(Instant.now())       ← 기간 검증
  ├── 3. initStockIfAbsent(eventId, totalQty, ttl)  ← Redis SET NX
  ├── 4. tryIssueCoupon(eventId, userId, ttl)        ← Redis Lua (원자적)
  │     ├── SOLD_OUT(0)  → throw EVENT_SOLD_OUT
  │     ├── ALREADY_ISSUED(-1) → throw COUPON_ALREADY_ISSUED
  │     └── SUCCESS(1) → continue
  └── 5. txService.saveOrCompensate(eventId, userId) ← @Transactional DB Write
        ├── save CouponIssue
        ├── UK 충돌 → restoreStock + throw COUPON_ALREADY_ISSUED
        └── DB 오류 → compensate (SREM + INCR) + re-throw
```

> **동시성 보호 구간**: Step 3~4가 핵심. `initStockIfAbsent`의 SET NX가 재고 초기화를 딱 한 번만 보장하고, Lua 스크립트가 `DECR + SADD`를 원자적으로 실행하여 초과/중복 발급을 방지한다.

### 2.3 Dependencies

| 컴포넌트 | 의존 대상 | 용도 |
|----------|----------|------|
| 테스트 클래스 | `CouponIssueService` | 발급 메서드 직접 호출 (테스트 대상) |
| 테스트 클래스 | `EventRepository` | 이벤트 생성 (DB 저장) |
| 테스트 클래스 | `CouponIssueRepository` | 발급 건수 확인 (DB 조회) |
| 테스트 클래스 | `StringRedisTemplate` | Redis issued Set 크기 확인, beforeTest flushAll |
| 테스트 클래스 | `EventFixture` | 테스트 이벤트 객체 생성 |
| Testcontainers | PostgreSQL 18-alpine | DB 환경 |
| Testcontainers | Redis 7-alpine | Redis 환경 |
| Testcontainers | Kafka (apache/kafka-native) | 앱 부팅 의존성 충족 (테스트에서 직접 사용하지 않음) |

---

## 3. Detailed Design

### 3.1 Test Class Structure

```kotlin
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
import org.testcontainers.containers.KafkaContainer
import org.testcontainers.containers.PostgreSQLContainer
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

    // --- Helpers (3.2, 3.3 참조) ---
    // --- Lifecycle (3.4 참조) ---
    // --- Test Cases (3.5 ~ 3.8 참조) ---

}) {
    companion object {
        @ServiceConnection
        val postgres = PostgreSQLContainer(DockerImageName.parse("postgres:18-alpine"))
            .apply { start() }

        @ServiceConnection(name = "redis")
        val redis: GenericContainer<*> =
            GenericContainer(DockerImageName.parse("redis:7-alpine"))
                .withExposedPorts(6379)
                .apply { start() }

        @ServiceConnection
        val kafka = KafkaContainer(DockerImageName.parse("apache/kafka-native:latest"))
            .apply { start() }
    }
}
```

**설계 결정 — companion container vs IntegrationTestBase**:

Kotlin은 단일 클래스 상속만 허용하므로, `IntegrationTestBase`(abstract class)와 `FunSpec`(class)을 동시에 상속할 수 없다. 따라서 `FunSpec`만 상속하고 companion object에서 컨테이너를 직접 선언한다. `IntegrationTestBase`와 동일한 이미지·설정을 사용하여 호환성을 유지한다.

### 3.2 Helper: createOpenEvent

```kotlin
fun createOpenEvent(totalQuantity: Int): Event {
    val now = Instant.now()
    return eventRepository.save(
        EventFixture.openEvent(
            totalQuantity = totalQuantity,
            period = DateRange(
                startedAt = now.minusSeconds(3600),
                endedAt = now.plusSeconds(3600),
            ),
        )
    )
}
```

**설계 결정**:
- `EventFixture.openEvent()`은 내부적으로 `Event(...).also { it.open() }`을 호출하므로 EventStatus가 OPEN으로 설정된다
- `DEFAULT_START`(2026-07-01)는 테스트 실행 시점에 따라 `period.contains(Instant.now())`가 실패할 수 있으므로, **현재 시간 기준 ±1시간** 범위의 커스텀 DateRange를 전달한다
- `eventRepository.save()`로 DB에 영속화해야 `CouponIssueService.issue()`의 `findById`가 동작한다

### 3.3 Helper: concurrentExecute

```kotlin
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

    startLatch.countDown()                             // 동시 출발
    val completed = doneLatch.await(60, TimeUnit.SECONDS)
    executor.shutdown()
    executor.awaitTermination(5, TimeUnit.SECONDS)

    check(completed) { "concurrentExecute timed out: $taskCount tasks / $poolSize threads" }
}
```

**설계 결정 — `poolSize` 분리**:

| Parameter | 의미 | 기본값 |
|-----------|------|--------|
| `taskCount` | 전체 작업 수 (submit 횟수) | 필수 |
| `poolSize` | 동시 실행 스레드 수 (풀 크기) | min(taskCount, 200) |

- TC-01에서 `taskCount=10,000`이면 `poolSize=200`이 기본 적용. OS 스레드 제한(ulimit) 안에서 동작하면서도 충분한 동시성 부하를 생성한다
- `startLatch`: 작업 큐에 모든 태스크가 제출된 후, `countDown()`으로 동시 출발. poolSize 제한으로 200개씩 batch 실행되지만, 각 batch 내에서는 진짜 동시 실행 보장
- `doneLatch.await(60, SECONDS)`: 무한 대기 방지. 타임아웃 시 `check` assertion 실패로 명확히 테스트 실패
- `executor.awaitTermination(5, SECONDS)`: shutdown 후 잔여 태스크 정리 대기

> **이중 래치(Double Latch) 패턴**:
> - `startLatch(1)`: "준비" 신호. 모든 스레드가 `await()`에서 대기 → `countDown()`으로 동시 출발
> - `doneLatch(N)`: "완료" 신호. 각 스레드가 `finally { countDown() }`으로 완료 보고 → 메인 스레드가 `await()`으로 전체 완료 대기
>
> 이 패턴이 없으면 먼저 생성된 스레드가 먼저 실행을 시작하여, "동시" 요청이 아니라 "순차" 요청에 가까워진다.

### 3.4 beforeTest Cleanup

```kotlin
beforeTest {
    couponIssueRepository.deleteAllInBatch()
    redisTemplate.execute { connection ->
        connection.serverCommands().flushAll()
        null
    }
}
```

**설계 결정**:
- `deleteAllInBatch()`: `deleteAll()`보다 빠름 (개별 DELETE 대신 단건 TRUNCATE 쿼리)
- `flushAll()`: Testcontainers Redis는 테스트 전용이므로 전체 삭제 안전. 특정 패턴 삭제보다 확실
- Event는 삭제하지 않음: 각 테스트에서 UUID 기반으로 새 Event를 생성하므로 충돌 없음

### 3.5 TC-01: 초과 발급 검증

```kotlin
test("TC-01: 1,000개 쿠폰에 10,000건 동시 요청 시 정확히 1,000건만 발급된다") {
    // Given
    val totalQuantity = 1_000
    val taskCount = 10_000
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
                else -> throw e  // 예상치 못한 예외는 전파
            }
        }
    }

    // Then — 3중 검증
    successCount.get() shouldBe totalQuantity
    soldOutCount.get() shouldBe (taskCount - totalQuantity)
    couponIssueRepository.count() shouldBe totalQuantity.toLong()
}
```

**설계 결정**:
- `UUID.randomUUID()`: 각 요청마다 다른 userId → 중복 발급이 아닌 **초과 발급** 검증에 집중
- `AtomicInteger`: 멀티스레드에서 안전한 카운팅
- `when` 분기: `EVENT_SOLD_OUT`만 정상 처리, 그 외 예외(예: DB 연결 오류)는 테스트 실패로 전파
- **3중 검증**: successCount + soldOutCount + DB count가 모두 일치해야 통과. 하나라도 불일치하면 Lua 스크립트의 원자성이 깨진 것

### 3.6 TC-02: 중복 발급 검증

```kotlin
test("TC-02: 동일 userId로 100 동시 요청 시 1건만 발급된다") {
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
```

**설계 결정**:
- `totalQuantity = 100`: 재고 부족이 아닌 **중복** 거부를 검증하기 위해 충분한 재고 확보
- `sameUserId`: 모든 스레드가 동일 userId로 요청 → Lua `SISMEMBER` + DB UK가 중복 방지
- 성공 1건 + 중복 99건 = 총 100건: 누락 없이 모든 요청이 처리되었음을 확인

### 3.7 TC-03: 매진 후 요청 검증

```kotlin
test("TC-03: 이미 매진된 이벤트에 1,000건 요청 시 전부 매진 응답") {
    // Given — 수량 5인 이벤트를 순차 발급으로 매진시킴
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
    couponIssueRepository.count() shouldBe preIssuedCount  // 추가 발급 없음
}
```

**설계 결정 — 순차 선발급 방식**:
- Plan의 `totalQuantity = 0` 방식은 Entity init 블록의 `totalQuantity > 0` 불변식 검증에 걸릴 수 있음
- 대신 `totalQuantity = 5`로 생성 → 5건 순차 발급 → 매진 상태 확정
- 이 방식은 실제 운영 시나리오와 동일: "발급이 진행되다가 매진된 후 추가 요청"
- `preIssuedCount`로 선발급 건수를 기록해두고, 동시 요청 후에도 변하지 않았음을 검증

### 3.8 TC-04: Redis-DB 정합성 검증

```kotlin
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
```

**설계 결정**:
- `coupon:issued:{${event.id}}`: Redis hash tag `{}`는 클러스터 환경에서 동일 슬롯 보장. Testcontainers 단일 노드에서도 키 패턴은 실제와 동일하게 유지
- 두 값 비교: DB count와 Redis Set 크기가 같아야 Lua → DB 저장 경로에 누락이 없음을 증명
- `catch (_: BusinessException)`: TC-04의 관심사는 정합성이므로 매진 예외는 단순 무시

---

## 4. Thread Pool Strategy

| TC | taskCount | poolSize | 근거 |
|----|:---------:|:--------:|------|
| TC-01 | 10,000 | 200 (기본값) | 최대 동시성. 200 스레드가 큐에서 작업을 꺼내 10,000건 처리 |
| TC-02 | 100 | 100 (1:1) | 소규모이므로 전수 동시 실행. 중복 발급 검증은 모든 요청이 "진짜 동시"여야 의미 있음 |
| TC-03 | 1,000 | 200 (기본값) | 매진 경로(읽기 only)라 빠르게 완료 |
| TC-04 | 1,000 | 200 (기본값) | Redis-DB 정합성 검증 |

**왜 poolSize 기본값 = 200인가?**

- Linux 기본 `ulimit -u`는 보통 4096~65536. 200개 스레드는 안전 범위
- 200개 스레드가 10,000건 처리: 스레드당 평균 50번 실행. 이는 실전의 "스레드 풀이 요청을 큐잉하여 처리하는" 패턴과 유사
- CI 환경(GitHub Actions, Jenkins)에서도 200 스레드 수준은 안정적으로 동작

---

## 5. Timeout & Stability

### 5.1 doneLatch 타임아웃

`concurrentExecute` 헬퍼에서 `doneLatch.await(60, TimeUnit.SECONDS)` 적용.

| TC | 예상 소요 | 타임아웃 | 여유 |
|----|:---------:|:--------:|:----:|
| TC-01 | 5~15s | 60s | 4~12x |
| TC-02 | 1~3s | 60s | 20x+ |
| TC-03 | 1~2s | 60s | 30x+ |
| TC-04 | 3~8s | 60s | 7~20x |

### 5.2 데드락 방지

- `finally { doneLatch.countDown() }`: 예외 발생 시에도 래치 감소 보장
- `executor.shutdown()` + `awaitTermination(5s)`: 스레드 풀 정리
- 예상치 못한 예외 전파: `when` 분기의 `else → throw e`로 테스트 실패 유도
- `check(completed)`: 타임아웃 발생 시 명확한 에러 메시지

---

## 6. File Structure

```
src/test/kotlin/com/beomjin/springeventlab/
├── coupon/
│   ├── EventCrudIntegrationTest.kt         ← EXISTING (L4)
│   └── CouponIssueConcurrencyTest.kt       ← NEW (L4 동시성)
├── support/
│   ├── IntegrationTestBase.kt              ← EXISTING (참고용, 상속하지 않음)
│   └── EventFixture.kt                     ← EXISTING (openEvent 활용)
└── io/kotest/provided/
    └── ProjectConfig.kt                    ← EXISTING (Kotest extensions)
```

---

## 7. Implementation Order

| Step | Description | Depends On |
|------|-------------|------------|
| 1 | `CouponIssueConcurrencyTest.kt` 생성 — 클래스 골격 + companion containers + imports | — |
| 2 | `beforeTest` 초기화 로직 (DB deleteAllInBatch + Redis flushAll) | Step 1 |
| 3 | `concurrentExecute` 헬퍼 (이중 래치 + poolSize 분리) | Step 1 |
| 4 | `createOpenEvent` 헬퍼 (EventFixture + 커스텀 period) | Step 1 |
| 5 | TC-01 초과 발급 검증 + 테스트 실행 확인 | Step 2, 3, 4 |
| 6 | TC-02 중복 발급 검증 | Step 2, 3, 4 |
| 7 | TC-03 매진 후 요청 검증 (순차 선발급 → 동시 요청) | Step 2, 3, 4 |
| 8 | TC-04 Redis-DB 정합성 검증 | Step 2, 3, 4 |
| 9 | 전체 테스트 실행 + poolSize 튜닝 | Step 5-8 |

---

## 8. Success Criteria

- [ ] TC-01: 1,000개 쿠폰, 10,000건 동시 요청 → 정확히 1,000건 발급 (초과 0건)
- [ ] TC-02: 동일 userId 100건 동시 → 1건만 발급 (중복 0건)
- [ ] TC-03: 매진 이벤트 1,000건 → 추가 발급 0건
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
| 0.3 | 2026-04-16 | Plan 검증 반영: concurrentExecute poolSize 분리, TC-03 순차 선발급 방식, EventFixture 활용, CouponIssueService 흐름도 추가, Key Insight (Event.issue 미호출) 추가 | beomjin |
