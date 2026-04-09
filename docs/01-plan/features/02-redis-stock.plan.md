# Redis Stock Management Planning Document

> **Summary**: Redis 원자적 연산(DECR)으로 선착순 재고를 차감하고 초과 발급을 방지하는 핵심 로직
>
> **Project**: spring-event-lab
> **Version**: 0.0.1-SNAPSHOT
> **Author**: beomjin
> **Date**: 2026-04-09
> **Status**: Draft
> **Roadmap**: [flash-sale.plan.md](flash-sale.plan.md) (2/5)
> **Depends On**: 01-event-crud

---

## Executive Summary

| Perspective | Content |
|-------------|---------|
| **Problem** | DB 행 락(Row Lock)으로 재고를 차감하면 동시 요청 시 데드락, 커넥션 풀 고갈, 초과 발급 발생 |
| **Solution** | Redis DECR 원자적 연산으로 메모리에서 재고를 차감하여 DB 부하 없이 수량을 제어 |
| **Function/UX Effect** | 유저가 발급 요청 시 100ms 이내에 성공/매진 응답을 즉시 받음 |
| **Core Value** | 단일 스레드 Redis의 원자성을 활용하여 락 없이도 정확한 수량 제어 보장 |

---

## 1. Overview

### 1.1 Purpose

이벤트 시작 시 Redis에 재고 수량을 로드하고, 유저 요청마다 Redis DECR로 원자적으로 차감하여
DB에 부하를 주지 않으면서도 **초과 발급을 완벽히 방지**하는 구조를 만든다.

### 1.2 학습 포인트

- **왜 DB 락이 아니라 Redis인가?**: DB 비관적 락 vs Redis DECR 성능 차이
- **Redis 원자적 연산**: 단일 스레드 모델, DECR이 왜 Thread-Safe한지
- **중복 발급 방지**: Redis Set(SADD/SISMEMBER) 활용
- **Redis-DB 동기화**: 이벤트 오픈 시 DB → Redis 수량 로드
- **Fallback 패턴**: Redis 장애 시 DB 비관적 락으로 Fallback (Resilience4j)

---

## 2. Scope

### 2.1 In Scope

- [ ] Redis 재고 로드: 이벤트 오픈 시 `coupon:stock:{eventId}` 에 totalQuantity 세팅
- [ ] 선착순 발급 API: `POST /api/v1/events/{eventId}/issue`
- [ ] Redis DECR로 수량 차감 + 결과 < 0이면 INCR 롤백 + 매진 응답
- [ ] Redis Set으로 중복 발급 방지 (SADD/SISMEMBER)
- [ ] 이벤트 시간 검증 (오픈 전/종료 후 요청 차단)
- [ ] 발급 성공 시 DB에 직접 저장 (Kafka는 다음 feature에서)
- [ ] Redis Key TTL 관리

### 2.2 Out of Scope

- Kafka 비동기 처리 (kafka-consumer에서)
- 대기열 (waiting-queue에서)
- 분산 락 (단일 인스턴스 기준)

---

## 3. Requirements

### 3.1 Functional Requirements

| ID | Requirement | Priority | Status |
|----|-------------|----------|--------|
| FR-01 | 이벤트 오픈 시 Redis에 재고 수량 로드 | High | Pending |
| FR-02 | 발급 API: Redis DECR로 수량 차감, 즉시 응답 | High | Pending |
| FR-03 | 수량 소진 시 매진 응답 (Redis에서 판단, DB 조회 없음) | High | Pending |
| FR-04 | 동일 유저 중복 발급 방지 (Redis Set + DB Unique) | High | Pending |
| FR-05 | 이벤트 시간 범위 외 요청 차단 | Medium | Pending |
| FR-06 | Redis 장애 시 CircuitBreaker Fallback | Low | Pending |

### 3.2 Non-Functional Requirements

| Category | Criteria | Measurement Method |
|----------|----------|-------------------|
| Performance | 발급 API p99 < 100ms | Actuator metrics |
| Consistency | 초과 발급 0건 | 동시성 테스트 (concurrency-test에서 검증) |

---

## 4. Core Logic: Redis 발급 Flow

```
1. POST /api/v1/events/{eventId}/issue?userId={userId}

2. [시간 검증] 현재 시각이 startedAt ~ endedAt 범위인지 확인
   └─ 범위 밖 → 400 Bad Request

3. [중복 체크] SISMEMBER coupon:issued:{eventId} {userId}
   └─ 이미 존재 → 409 Conflict ("이미 발급됨")

4. [수량 차감] DECR coupon:stock:{eventId}
   └─ 결과 < 0 → INCR coupon:stock:{eventId} (롤백) + 410 Gone ("매진")
   └─ 결과 >= 0 → 발급 성공!

5. [발급 기록] SADD coupon:issued:{eventId} {userId}

6. [DB 저장] CouponIssue INSERT (event_id, user_id)

7. → 200 OK 응답
```

---

## 5. Redis Key Design

| Key Pattern | Type | Purpose | TTL |
|-------------|------|---------|-----|
| `coupon:stock:{eventId}` | String | 잔여 수량 (DECR 대상) | 이벤트 종료 + 1h |
| `coupon:issued:{eventId}` | Set | 발급된 userId 집합 | 이벤트 종료 + 1h |

---

## 6. Success Criteria

- [ ] 발급 API가 Redis DECR로 수량을 차감한다
- [ ] 수량이 0이 되면 이후 요청은 모두 매진 응답을 받는다
- [ ] 동일 유저가 2번 요청하면 두 번째는 거부된다
- [ ] Redis와 DB의 발급 건수가 일치한다

---

## 7. Next Steps

1. [ ] Design 문서 작성
2. [ ] 구현
3. [ ] 다음 feature: `concurrency-test` (이 로직이 진짜 동시성 환경에서 안전한지 증명)

---

## Version History

| Version | Date | Changes | Author |
|---------|------|---------|--------|
| 0.1 | 2026-04-09 | Initial draft | beomjin |
