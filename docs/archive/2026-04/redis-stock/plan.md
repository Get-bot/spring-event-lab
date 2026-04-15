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
- **개별 명령 vs 복합 원자성**: 개별 Redis 명령은 원자적이지만 여러 명령 조합은 아니다 → Lua 스크립트 필요성
- **Lua 스크립트**: SISMEMBER+DECR+SADD를 하나의 원자적 블록으로 묶어 race condition 제거 (3 RTT → 1 RTT)
- **중복 발급 방지**: Redis Set(SADD/SISMEMBER) + DB Unique 제약 이중 방어
- **Redis-DB 동기화**: 이벤트 오픈 시 DB → Redis 수량 로드, 실패 시 보상 전략
- **Fallback 패턴**: Redis 장애 시 DB 비관적 락으로 Fallback (Resilience4j)

---

## 2. Scope

### 2.1 In Scope

- [ ] Redis 재고 로드: 발급 API 최초 호출 시 Lazy Init (`SET NX`)로 `coupon:stock:{eventId}` 에 totalQuantity 세팅
- [ ] 선착순 발급 API: `POST /api/v1/events/{eventId}/issue`
- [ ] **Lua 스크립트**로 SISMEMBER + GET/DECR + SADD를 하나의 원자적 블록으로 실행
- [ ] 이벤트 시간 검증 (오픈 전/종료 후 요청 차단)
- [ ] 발급 성공 시 DB에 직접 저장 (Kafka는 다음 feature에서)
- [ ] Redis→DB 실패 시 보상 처리 (SREM + INCR 롤백)
- [ ] Redis Key TTL 관리 (재고 로드 시 `SET ... EX` 또는 `EXPIRE`)

### 2.2 Out of Scope

- Kafka 비동기 처리 (kafka-consumer에서)
- 대기열 (waiting-queue에서)
- 분산 락 (단일 인스턴스 기준)

---

## 3. Requirements

### 3.1 Functional Requirements

| ID | Requirement | Priority | Status |
|----|-------------|----------|--------|
| FR-01 | 발급 API 첫 호출 시 Redis에 재고 Lazy Init (`SET NX` + `EXPIRE`) | High | Pending |
| FR-02 | Lua 스크립트로 중복체크+차감+기록을 원자적 실행 | High | Pending |
| FR-03 | 수량 소진 시 매진 응답 (Redis에서 판단, DB 조회 없음) | High | Pending |
| FR-04 | 동일 유저 중복 발급 방지 (Lua 내 SISMEMBER + DB Unique 이중 방어) | High | Pending |
| FR-05 | 이벤트 시간 범위 외 요청 차단 | Medium | Pending |
| FR-06 | Redis→DB 저장 실패 시 보상 처리 (SREM + INCR) | High | Pending |
| FR-07 | Redis 장애 시 CircuitBreaker Fallback | Low | Pending |

### 3.2 Non-Functional Requirements

| Category | Criteria | Measurement Method |
|----------|----------|-------------------|
| Performance | 발급 API p99 < 100ms (Lua 1 RTT) | Actuator metrics |
| Consistency | 초과 발급 0건 | 동시성 테스트 (concurrency-test에서 검증) |
| Reliability | DB 실패 시 Redis 보상 성공률 100% (단일 인스턴스) | 통합 테스트 |

---

## 4. Core Logic: Redis 발급 Flow

### 4.1 API 엔드포인트

```
POST /api/v1/events/{eventId}/issue?userId={userId}
```

> **Note**: `userId`는 인증 미구현 상태의 임시 설계. 향후 SecurityContext에서 추출하도록 변경 예정.

### 4.2 Application 흐름

```
1. [시간 검증] 현재 시각이 startedAt ~ endedAt 범위인지 확인
   └─ 범위 밖 → EVENT_NOT_OPEN (400 Bad Request)

2. [재고 초기화] Redis에 stock 키가 없으면 Lazy Init
   └─ SET coupon:stock:{eventId} {totalQuantity} NX EX {ttl}

3. [Lua 스크립트 실행] 중복체크 + 수량차감 + 발급기록을 원자적 실행
   └─ 반환값 -1 → ALREADY_ISSUED (409 Conflict)
   └─ 반환값  0 → EVENT_SOLD_OUT (410 Gone)
   └─ 반환값  1 → 발급 성공, 다음 단계로

4. [DB 저장] CouponIssue INSERT (event_id, user_id)
   └─ 실패 시 → Redis 보상 처리 (SREM + INCR)

5. → 201 Created 응답
```

### 4.3 Lua 스크립트 (핵심)

개별 Redis 명령(SISMEMBER, DECR, SADD)은 각각 원자적이지만, **순차 호출 시 명령 사이에 다른 클라이언트 요청이 끼어들 수 있다**. Lua 스크립트는 실행 중 다른 어떤 명령도 끼어들 수 없음이 보장되므로, 3개 연산을 하나의 원자적 블록으로 묶는다.

```lua
-- issue_coupon.lua
-- KEYS[1] = coupon:stock:{eventId}
-- KEYS[2] = coupon:issued:{eventId}
-- ARGV[1] = userId

-- 1) 재고 확인 — 매진이 전체 트래픽의 99%+ → 읽기 1회로 즉시 탈출
local stock = tonumber(redis.call('GET', KEYS[1]))
if stock == nil or stock <= 0 then
    return 0   -- 매진
end

-- 2) 중복 확인 — 읽기 1회 추가
if redis.call('SISMEMBER', KEYS[2], ARGV[1]) == 1 then
    return -1  -- 이미 발급됨
end

-- 3) 성공 경로에서만 쓰기 발생
redis.call('DECR', KEYS[1])
redis.call('SADD', KEYS[2], ARGV[1])
return 1       -- 발급 성공
```

**왜 Check-then-Act(읽기 우선)인가?**
- Flash sale에서 매진 후 요청이 전체의 99%+를 차지
- DECR-first(Act-then-Check)는 매진 경로에서도 쓰기 4회(SADD+DECR+INCR+SREM)가 발생
- GET 선검사 방식은 매진 경로를 **읽기 1회, 쓰기 0회**로 처리 → 성능 극대화
- 쓰기가 비싼 이유: AOF 영속화, 레플리카 전파, 메모리 할당/해제, Lua 블로킹 시간 증가

**성능 이점**: 네트워크 3 RTT → 1 RTT + 매진 경로 쓰기 제거

### 4.4 Redis→DB 실패 보상 전략

Lua 스크립트 성공 후 DB INSERT가 실패하면 Redis와 DB 간 불일치가 발생한다.

```
[보상 처리]
1. SREM coupon:issued:{eventId} {userId}   ← 발급 기록 제거
2. INCR coupon:stock:{eventId}             ← 재고 복원
3. 예외를 클라이언트에 전파 (재시도 유도)
```

> 이 보상 처리도 실패할 수 있으나, 단일 인스턴스 기준에서는 충분하다.
> 분산 환경에서의 완전한 정합성은 Kafka 기반 비동기 저장(04-kafka-consumer)에서 해결한다.

---

## 5. Redis Key Design

| Key Pattern | Type | Purpose | TTL | 설정 시점 |
|-------------|------|---------|-----|----------|
| `coupon:stock:{eventId}` | String | 잔여 수량 (DECR 대상) | 이벤트 종료 + 1h | Lazy Init (`SET NX EX`) |
| `coupon:issued:{eventId}` | Set | 발급된 userId 집합 | 이벤트 종료 + 1h | 첫 SADD 시 자동 생성, `EXPIRE` 별도 설정 |

---

## 6. Error Code 매핑

| 상황 | ErrorCode | HTTP Status |
|------|-----------|-------------|
| 이벤트 미오픈/종료 | `EVENT_NOT_OPEN` | 400 Bad Request |
| 이미 발급됨 | `ALREADY_ISSUED` | 409 Conflict |
| 매진 | `EVENT_SOLD_OUT` | 410 Gone |
| Redis 장애 Fallback 실패 | `INTERNAL_SERVER_ERROR` | 500 Internal Server Error |

---

## 7. Success Criteria

- [ ] Lua 스크립트가 중복체크+차감+기록을 원자적으로 실행한다
- [ ] 수량이 0이 되면 이후 요청은 모두 매진 응답을 받는다
- [ ] 동일 유저가 2번 요청하면 두 번째는 거부된다 (Lua SISMEMBER + DB Unique 이중 방어)
- [ ] Redis와 DB의 발급 건수가 일치한다
- [ ] DB 저장 실패 시 Redis 보상 처리(SREM+INCR)가 동작한다

---

## 8. Next Steps

1. [ ] Design 문서 작성 (`/pdca design redis-stock`)
2. [ ] 구현 — Lua 스크립트(`issue_coupon.lua`) + Spring `RedisScript<Long>` 연동
3. [ ] 다음 feature: `concurrency-test` (이 로직이 진짜 동시성 환경에서 안전한지 증명)

---

## Version History

| Version | Date | Changes | Author |
|---------|------|---------|--------|
| 0.1 | 2026-04-09 | Initial draft | beomjin |
| 0.2 | 2026-04-15 | Lua 스크립트 원자화, Redis→DB 보상 전략, Lazy Init, ErrorCode 매핑 추가 | beomjin |
