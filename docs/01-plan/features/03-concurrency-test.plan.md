# Concurrency Test Planning Document

> **Summary**: 선착순 발급 로직이 동시성 환경에서 정말 안전한지 증명하는 통합 테스트
>
> **Project**: spring-event-lab
> **Version**: 0.0.1-SNAPSHOT
> **Author**: beomjin
> **Date**: 2026-04-15
> **Status**: Draft (v0.2)
> **Roadmap**: [flash-sale.plan.md](flash-sale.plan.md) (3/5)
> **Depends On**: 02-redis-stock (Lua 스크립트 + CouponIssueService 구현 완료)

---

## Executive Summary

| Perspective | Content |
|-------------|---------|
| **Problem** | 단건 테스트에서는 잘 되지만, 수천 건 동시 요청 시 Lua 스크립트의 원자성이 실제로 초과/중복 발급을 완벽히 방지하는지 코드 리뷰만으로는 증명할 수 없다 |
| **Solution** | Kotest 6.1.0 FunSpec + ExecutorService + CountDownLatch로 동시 요청을 시뮬레이션하는 통합 테스트 작성. IntegrationTestBase(Testcontainers)로 실제 Redis + PostgreSQL 환경에서 실행 |
| **Function/UX Effect** | "1,000개 쿠폰에 10,000건 동시 요청 → 정확히 1,000건만 발급" 을 `./gradlew test`로 증명 |
| **Core Value** | Redis Lua 스크립트 기반 재고 관리의 정합성을 객관적 수치로 검증하고, 동시성 테스트 패턴을 학습 |

---

## 1. Overview

### 1.1 Purpose

redis-stock에서 구현한 선착순 발급 로직이 **진짜 동시성 환경에서 안전한지** 직접 증명한다.
"동작하는 것 같다"가 아니라 "10,000건 동시에 쏴도 정확히 1,000건만 발급된다"를 테스트 코드로 보여준다.

### 1.2 학습 포인트

- **동시성 테스트 패턴**: `ExecutorService` + `CountDownLatch` 조합으로 N개 스레드가 동시에 출발하도록 제어
- **IntegrationTestBase 재사용**: event-crud-test에서 구축한 Testcontainers 인프라(`PostgreSQL + Redis + Kafka`)를 그대로 활용
- **Kotest FunSpec**: event-crud-test에서 확립된 테스트 프레임워크로 일관성 유지
- **Service 레벨 동시성 테스트**: HTTP 계층이 아닌 `CouponIssueService.issue()` 직접 호출로 순수 비즈니스 로직의 동시성 안전성 검증
- **테스트로 증명하기**: 동시성 버그는 코드 리뷰로 안 잡힌다, 테스트로만 잡힌다

---

## 2. Scope

### 2.1 In Scope

- [ ] 동시성 통합 테스트: N개 스레드로 `CouponIssueService.issue()` 동시 호출
- [ ] 초과 발급 검증: DB 발급 건수 == totalQuantity 확인
- [ ] 중복 발급 검증: 같은 userId로 동시 요청 시 1건만 발급
- [ ] IntegrationTestBase (Redis + PostgreSQL) 기반 테스트 환경
- [ ] Redis-DB 정합성 검증: Redis issued Set 크기 == DB 발급 건수

### 2.2 Out of Scope

- 부하 테스트 도구(k6, JMeter, Gatling) 활용 — 로컬 통합 테스트만
- Kafka Consumer 관련 테스트 (04-kafka-consumer에서)
- DB 비관적 락 vs Redis 성능 비교 (별도 벤치마크 feature에서)

---

## 3. Requirements

### 3.1 Test Scenarios

| ID | Scenario | 기대 결과 | Priority |
|----|----------|----------|----------|
| TC-01 | 1,000개 쿠폰, 100 스레드 × 100 유저 (10,000건) | 정확히 1,000건 발급, 9,000건 매진(EVENT_SOLD_OUT) | High |
| TC-02 | 동일 userId로 100 동시 요청 | 1건만 발급, 99건 중복 거부(COUPON_ALREADY_ISSUED) | High |
| TC-03 | 이미 매진된 이벤트에 1,000건 요청 | 0건 발급, 전부 매진 응답 | Medium |
| TC-04 | Redis-DB 정합성: 발급 후 Redis issued Set 크기 == DB count | 수치 일치 | High |

---

## 4. Technical Dependencies

### 4.1 기존 인프라 재사용 (event-crud-test에서 구축)

| Component | Version | Source |
|-----------|---------|--------|
| Kotest | 6.1.0 | `build.gradle.kts` (이미 설정됨) |
| MockK | 1.14.9 | `build.gradle.kts` (이미 설정됨) |
| springmockk | 5.0.1 | `build.gradle.kts` (이미 설정됨) |
| Testcontainers | Latest | `build.gradle.kts` (이미 설정됨) |
| ProjectConfig | Kotest 6.1.0 | `src/test/kotlin/io/kotest/provided/ProjectConfig.kt` |
| IntegrationTestBase | PostgreSQL + Redis + Kafka | `src/test/kotlin/.../support/IntegrationTestBase.kt` |
| EventFixture | Event 생성 팩토리 | `src/test/kotlin/.../support/EventFixture.kt` |

### 4.2 redis-stock 구현체 (테스트 대상)

| Component | File | Purpose |
|-----------|------|---------|
| CouponIssueService | `coupon/service/CouponIssueService.kt` | 발급 유스케이스 orchestration (트랜잭션 없음) |
| CouponIssueTxService | `coupon/service/CouponIssueWriter.kt` | @Transactional DB 저장 + UK-aware 보상 |
| RedisStockRepository | `coupon/repository/RedisStockRepository.kt` | Redis 재고 연산 (hash tag 키 패턴) |
| CouponIssueRepository | `coupon/repository/CouponIssueRepository.kt` | DB 저장 |
| IssueResult | `coupon/repository/IssueResult.kt` | Lua 반환 코드 enum |
| issue_coupon.lua | `resources/scripts/issue_coupon.lua` | 원자적 재고 차감 + issued Set TTL |

---

## 5. Test Pattern

### 5.1 테스트 클래스 구조

```kotlin
// Kotest 6.1.0 FunSpec + IntegrationTestBase
class CouponIssueConcurrencyTest : IntegrationTestBase(), FunSpec({

    // 의존성 주입 (Spring Context에서)
    val couponIssueService = inject<CouponIssueService>()
    val eventRepository = inject<EventRepository>()
    val couponIssueRepository = inject<CouponIssueRepository>()
    val redisTemplate = inject<StringRedisTemplate>()

    beforeTest {
        // 각 테스트 전 DB + Redis 초기화
        couponIssueRepository.deleteAll()
        // Redis 키 정리는 flushAll 또는 특정 패턴 삭제
    }
})
```

> **학습**: IntegrationTestBase를 상속하면 Testcontainers가 자동으로 PostgreSQL + Redis를 시작하고 `@ServiceConnection`으로 프로퍼티를 매핑한다. 별도 `@Container` 선언 불필요.

### 5.2 동시성 테스트 골격

```kotlin
test("TC-01: 1,000개 쿠폰에 10,000건 동시 요청 시 정확히 1,000건만 발급된다") {
    // Given
    val totalQuantity = 1_000
    val threadCount = 100
    val event = eventRepository.save(
        EventFixture.openEvent(
            totalQuantity = totalQuantity,
            period = DateRange(
                Instant.now().minusSeconds(3600),
                Instant.now().plusSeconds(3600),
            ),
        )
    )

    val executor = Executors.newFixedThreadPool(threadCount)
    val startLatch = CountDownLatch(1)        // 동시 출발 신호
    val doneLatch = CountDownLatch(10_000)    // 완료 대기

    // When — 10,000건 동시 발급 요청
    repeat(10_000) { i ->
        executor.submit {
            startLatch.await()   // 모든 스레드가 준비될 때까지 대기
            try {
                couponIssueService.issue(event.id, UUID.randomUUID())
            } catch (_: BusinessException) {
                // 매진/중복은 예상된 예외
            } finally {
                doneLatch.countDown()
            }
        }
    }
    startLatch.countDown()  // 동시 출발!
    doneLatch.await()
    executor.shutdown()

    // Then — 정확히 1,000건만 발급
    couponIssueRepository.count() shouldBe totalQuantity.toLong()
}
```

> **startLatch 패턴**: `CountDownLatch(1)`로 모든 스레드가 준비된 후 `countDown()`으로 동시에 출발시킨다. 이렇게 해야 스레드 생성 시간 차이 없이 진짜 동시 요청을 시뮬레이션할 수 있다.

### 5.3 중복 발급 검증

```kotlin
test("TC-02: 동일 userId로 100 동시 요청 시 1건만 발급된다") {
    // Given
    val event = eventRepository.save(
        EventFixture.openEvent(
            totalQuantity = 100,
            period = DateRange(
                Instant.now().minusSeconds(3600),
                Instant.now().plusSeconds(3600),
            ),
        )
    )
    val sameUserId = UUID.randomUUID()
    val threadCount = 100

    val executor = Executors.newFixedThreadPool(threadCount)
    val startLatch = CountDownLatch(1)
    val doneLatch = CountDownLatch(threadCount)

    // When — 같은 userId로 100건 동시 요청
    repeat(threadCount) {
        executor.submit {
            startLatch.await()
            try {
                couponIssueService.issue(event.id, sameUserId)
            } catch (_: BusinessException) {
                // COUPON_ALREADY_ISSUED 예상
            } finally {
                doneLatch.countDown()
            }
        }
    }
    startLatch.countDown()
    doneLatch.await()
    executor.shutdown()

    // Then — 정확히 1건만 발급
    couponIssueRepository.count() shouldBe 1
}
```

### 5.4 Redis-DB 정합성 검증

```kotlin
test("TC-04: 발급 후 Redis issued Set 크기 == DB 발급 건수") {
    // TC-01 실행 후 추가 검증
    val issuedSetSize = redisTemplate.opsForSet()
        .size("coupon:issued:{${event.id}}")

    issuedSetSize shouldBe couponIssueRepository.count()
}
```

---

## 6. Test File Structure

```
src/test/kotlin/com/beomjin/springeventlab/
├── coupon/
│   └── CouponIssueConcurrencyTest.kt    ← NEW (L4 Integration)
└── support/
    ├── IntegrationTestBase.kt            ← EXISTING (재사용)
    └── EventFixture.kt                   ← EXISTING (재사용)
```

---

## 7. Success Criteria

- [ ] TC-01 통과: 1,000개 쿠폰에 10,000건 동시 요청 → 정확히 1,000건 발급 (초과 발급 0건)
- [ ] TC-02 통과: 동일 userId 100건 동시 요청 → 1건만 발급 (중복 발급 0건)
- [ ] TC-03 통과: 매진 후 요청 → 0건 발급
- [ ] TC-04 통과: Redis issued Set 크기 == DB 발급 건수
- [ ] IntegrationTestBase 기반으로 CI 환경에서도 재현 가능
- [ ] 전체 동시성 테스트 30초 이내 완료

---

## 8. Next Steps

1. [ ] Design 문서 작성 (`/pdca design concurrency-test`)
2. [ ] 구현 및 테스트 실행
3. [ ] 다음 feature: `04-kafka-consumer`

---

## Version History

| Version | Date | Changes | Author |
|---------|------|---------|--------|
| 0.1 | 2026-04-09 | Initial draft | beomjin |
| 0.2 | 2026-04-15 | event-crud-test 학습 반영: Kotest 6.1.0 FunSpec, IntegrationTestBase 재사용, startLatch 동시 출발 패턴, Redis-DB 정합성 검증 추가, DB 비관적 락 비교(TC-04) 제거 | beomjin |
| 0.3 | 2026-04-16 | redis-stock 구현 반영: Redis 키 hash tag `{$eventId}`, CouponIssueTxService 분리, issued Set Lua TTL | beomjin |
