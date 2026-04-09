# Concurrency Test Planning Document

> **Summary**: 선착순 발급 로직이 동시성 환경에서 정말 안전한지 증명하는 통합 테스트
>
> **Project**: spring-event-lab
> **Version**: 0.0.1-SNAPSHOT
> **Author**: beomjin
> **Date**: 2026-04-09
> **Status**: Draft
> **Roadmap**: [flash-sale.plan.md](flash-sale.plan.md) (3/5)
> **Depends On**: 02-redis-stock

---

## Executive Summary

| Perspective | Content |
|-------------|---------|
| **Problem** | 단건 테스트에서는 잘 되지만, 수천 건 동시 요청 시 초과 발급/중복 발급이 발생할 수 있음 |
| **Solution** | ExecutorService + CountDownLatch로 동시 요청을 시뮬레이션하는 통합 테스트 작성 |
| **Function/UX Effect** | "1,000개 쿠폰에 10,000건 동시 요청 → 정확히 1,000건만 발급" 을 코드로 증명 |
| **Core Value** | Redis DECR 기반 재고 관리의 정합성을 객관적 수치로 검증 |

---

## 1. Overview

### 1.1 Purpose

redis-stock에서 구현한 선착순 발급 로직이 **진짜 동시성 환경에서 안전한지** 직접 증명한다.
"동작하는 것 같다"가 아니라 "10,000건 동시에 쏴도 정확히 1,000건만 발급된다"를 테스트 코드로 보여준다.

### 1.2 학습 포인트

- **동시성 테스트 패턴**: ExecutorService + CountDownLatch 조합
- **Testcontainers + @ServiceConnection**: Spring Boot 3.1+/4.0의 `@ServiceConnection`으로 컨테이너 자동 연결 (수동 `@DynamicPropertySource` 불필요)
- **Race Condition 이해**: 왜 단순한 `if (stock > 0) stock--` 가 위험한지
- **테스트로 증명하기**: 동시성 버그는 코드 리뷰로 안 잡힌다, 테스트로만 잡힌다

---

## 2. Scope

### 2.1 In Scope

- [ ] 동시성 통합 테스트: N개 스레드로 동시 발급 요청
- [ ] 초과 발급 검증: 발급 건수 == totalQuantity 확인
- [ ] 중복 발급 검증: 같은 userId로 동시 요청 시 1건만 발급
- [ ] Testcontainers (Redis + PostgreSQL) 기반 테스트 환경
- [ ] (선택) DB 비관적 락 버전과 Redis 버전 성능 비교 테스트

### 2.2 Out of Scope

- 부하 테스트 도구(k6, JMeter) 활용 - 로컬 통합 테스트만
- Kafka Consumer 관련 테스트

---

## 3. Requirements

### 3.1 Test Scenarios

| ID | Scenario | 기대 결과 | Priority |
|----|----------|----------|----------|
| TC-01 | 1,000개 쿠폰, 100 스레드 × 100 요청 (10,000건) | 정확히 1,000건 발급, 9,000건 매진 | High |
| TC-02 | 동일 userId로 100 동시 요청 | 1건만 발급, 99건 중복 거부 | High |
| TC-03 | 이미 매진된 이벤트에 1,000건 요청 | 0건 발급, 전부 매진 응답 | Medium |
| TC-04 | (선택) DB 비관적 락 vs Redis DECR 수행 시간 비교 | Redis가 유의미하게 빠름 | Low |

---

## 4. Test Pattern

### 4.1 Testcontainers 설정 (Spring Boot 4.0 방식)

```kotlin
// @ServiceConnection: Spring Boot 3.1+/4.0에서 컨테이너 → 프로퍼티 자동 매핑
// 기존 @DynamicPropertySource로 수동 매핑하던 것을 대체
@SpringBootTest
@Testcontainers
class ConcurrencyTest {

    companion object {
        @Container
        @ServiceConnection
        val postgres = PostgreSQLContainer("postgres:18")

        @Container
        @ServiceConnection
        val redis = GenericContainer("redis:8").withExposedPorts(6379)
    }
}
```

> **학습**: `@ServiceConnection`은 컨테이너 타입을 감지하여 `spring.datasource.url`, `spring.data.redis.host` 등을 자동 설정한다.
> 기존 `@DynamicPropertySource`로 수동 매핑하던 보일러플레이트를 제거하는 Spring Boot 3.1+ 기능이다.

### 4.2 동시성 테스트 골격

```kotlin
@Test
fun `1000개 쿠폰에 10000건 동시 요청 시 정확히 1000건만 발급된다`() {
    // Given
    val threadCount = 100
    val requestPerThread = 100
    val totalQuantity = 1000
    // 이벤트 생성 + Redis 재고 로드

    val executor = Executors.newFixedThreadPool(threadCount)
    val latch = CountDownLatch(threadCount * requestPerThread)

    // When - 동시에 쏜다
    repeat(threadCount * requestPerThread) { i ->
        executor.submit {
            try {
                // 발급 API 호출 (userId = i)
            } finally {
                latch.countDown()
            }
        }
    }
    latch.await()

    // Then - 정확히 1000건만 발급되었는지 확인
    val issuedCount = couponIssueRepository.count()
    assertThat(issuedCount).isEqualTo(totalQuantity.toLong())
}
```

---

## 5. Success Criteria

- [ ] TC-01 통과: 초과 발급 0건
- [ ] TC-02 통과: 중복 발급 0건
- [ ] 테스트가 Testcontainers로 CI 환경에서도 재현 가능

---

## 6. Next Steps

1. [ ] redis-stock 구현 완료 후 테스트 작성
2. [ ] 다음 feature: `kafka-consumer`

---

## Version History

| Version | Date | Changes | Author |
|---------|------|---------|--------|
| 0.1 | 2026-04-09 | Initial draft | beomjin |
