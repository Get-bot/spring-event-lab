# Kafka Consumer Planning Document

> **Summary**: 발급 성공 이벤트를 Kafka로 비동기 처리하여 DB 부하를 분산하는 Peak Load Shifting 구현
>
> **Project**: spring-event-lab
> **Version**: 0.0.1-SNAPSHOT
> **Author**: beomjin
> **Date**: 2026-04-09
> **Status**: Draft
> **Roadmap**: [flash-sale.plan.md](flash-sale.plan.md) (4/5)
> **Depends On**: 02-redis-stock

---

## Executive Summary

| Perspective | Content |
|-------------|---------|
| **Problem** | Redis에서 수량 확보 성공한 요청을 곧바로 DB에 INSERT하면, 스파이크 시 DB 커넥션 풀 고갈 |
| **Solution** | Kafka를 버퍼로 활용하여 발급 이벤트를 큐에 쌓고, Consumer가 DB 소화 속도에 맞춰 처리 (Peak Load Shifting) |
| **Function/UX Effect** | 유저 응답 속도는 변함없고(Redis에서 즉시 응답), DB는 안정적인 속도로 저장 처리 |
| **Core Value** | 순간 1만 TPS 요청이 와도 DB는 초당 100건씩 안정적으로 소화 - 인프라 비용 절감 |

---

## 1. Overview

### 1.1 Purpose

redis-stock에서는 발급 성공 시 동기적으로 DB에 저장했다. 이제 이 부분을 **Kafka Producer/Consumer로 분리**하여,
DB 쓰기를 비동기 버퍼링한다. 이것이 **Peak Load Shifting** 패턴이다.

### 1.2 학습 포인트

- **왜 바로 DB에 안 넣나?**: 동기 DB 쓰기의 한계 (커넥션 풀, 응답 시간)
- **Peak Load Shifting**: Kafka를 버퍼로 쓰는 패턴
- **Kafka Producer**: idempotent producer, acks=all 의미
- **Kafka Consumer**: manual commit, 멱등성 보장
- **DLT (Dead Letter Topic)**: `@RetryableTopic` + `@DltHandler`로 선언적 재시도/DLT 처리 (Spring Kafka 현대적 패턴)
- **Eventually Consistent**: 유저에겐 즉시 응답, DB 반영은 수 초 후 → 결과적 일관성

---

## 2. Scope

### 2.1 In Scope

- [ ] Kafka Topic 설정: `coupon-issue` (파티션 3)
- [ ] CouponIssueProducer: 발급 성공 시 Kafka 메시지 발행
- [ ] CouponIssueConsumer: 메시지 소비 → DB INSERT
- [ ] DLT 처리: 소비 실패 시 `coupon-issue.DLT` 로 이동
- [ ] redis-stock의 동기 DB 저장을 Kafka 비동기로 교체
- [ ] Consumer 멱등성: DB Unique 제약으로 중복 INSERT 방지
- [ ] 메시지 직렬화: JSON (Jackson)

### 2.2 Out of Scope

- Kafka 클러스터 운영 (단일 브로커)
- Schema Registry (Avro)
- Consumer 수평 확장 (파티션 리밸런싱)

---

## 3. Requirements

### 3.1 Functional Requirements

| ID | Requirement | Priority | Status |
|----|-------------|----------|--------|
| FR-01 | 발급 성공 시 Kafka에 CouponIssueMessage 발행 | High | Pending |
| FR-02 | Consumer가 메시지를 소비하여 coupon_issue 테이블에 INSERT | High | Pending |
| FR-03 | Consumer 실패 시 DLT로 메시지 이동 (3회 재시도 후) | High | Pending |
| FR-04 | 중복 메시지 소비 시 DB Unique 제약으로 무시 (멱등성) | High | Pending |
| FR-05 | Manual Offset Commit (DB 저장 성공 후 커밋) | Medium | Pending |

### 3.2 Non-Functional Requirements

| Category | Criteria | Measurement Method |
|----------|----------|-------------------|
| Durability | 메시지 유실 0건 | DLT 모니터링 |
| Throughput | Consumer 초당 500건 이상 처리 | 로그 기반 측정 |

---

## 4. Architecture: Before vs After

```
[Before - redis-stock]
유저 → Redis DECR → DB INSERT (동기) → 응답
                     ^^^^^^^^^^^^^^^^
                     스파이크 시 병목!

[After - kafka-consumer]
유저 → Redis DECR → Kafka Produce → 응답 (즉시!)
                         ↓ (비동기)
                    Kafka Consumer → DB INSERT (DB 속도에 맞춰)
```

---

## 5. Kafka Message Design

```json
// Topic: coupon-issue
{
  "eventId": 1,
  "userId": 42,
  "issuedAt": "2026-04-09T15:30:00"
}
```

| Config | Value | 이유 |
|--------|-------|------|
| `acks` | `all` | 메시지 유실 방지 |
| `enable.idempotence` | `true` | Producer 중복 발행 방지 |
| `auto-offset-reset` | `earliest` | Consumer 재시작 시 처음부터 |
| `enable-auto-commit` | `false` | DB 저장 후 수동 커밋 |
| `retries` | `3` | 실패 시 재시도 |

---

## 6. DLT 처리 패턴 (Spring Kafka)

> Spring Kafka는 두 가지 DLT 접근 방식을 제공한다. `@RetryableTopic`이 현대적이고 간결한 방식이다.

### 6.1 @RetryableTopic 방식 (권장)

```kotlin
@Component
class CouponIssueConsumer(
    private val couponIssueRepository: CouponIssueRepository,
) {
    @RetryableTopic(
        attempts = "4",                          // 최초 1회 + 재시도 3회
        backoff = BackOff(delay = 1000, multiplier = 2.0),
        dltTopicSuffix = ".DLT",
    )
    @KafkaListener(topics = ["coupon-issue"], groupId = "spring-event-lab")
    fun consume(message: CouponIssueMessage) {
        couponIssueRepository.save(CouponIssue(message.eventId, message.userId))
    }

    @DltHandler
    fun handleDlt(message: CouponIssueMessage) {
        // DLT 도착 메시지 로깅 + 모니터링 알림
        log.error("DLT 수신: eventId=${message.eventId}, userId=${message.userId}")
    }
}
```

> **학습**: `@RetryableTopic`은 자동으로 retry topic(`coupon-issue-retry-1000`, `-2000`, ...)과 DLT(`coupon-issue.DLT`)를 생성한다.
> 기존 `DefaultErrorHandler` + `DeadLetterPublishingRecoverer` 수동 설정을 대체하는 선언적 방식이다.

---

## 7. Success Criteria

- [ ] 발급 API에서 DB INSERT 코드가 제거되고 Kafka produce로 대체
- [ ] Consumer가 메시지를 정상 소비하여 DB에 저장
- [ ] Consumer 중단 후 재시작해도 메시지 유실 없음
- [ ] 동시성 테스트가 여전히 통과 (초과 발급 0건)

---

## 8. Next Steps

1. [ ] Design 문서 작성
2. [ ] 구현 + 기존 동시성 테스트 수정
3. [ ] 다음 feature: `waiting-queue`

---

## Version History

| Version | Date | Changes | Author |
|---------|------|---------|--------|
| 0.1 | 2026-04-09 | Initial draft | beomjin |
