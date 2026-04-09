# Waiting Queue Planning Document

> **Summary**: Redis Sorted Set 기반 대기열로 트래픽 과다 시 유저를 줄 세우고 순서대로 입장시키는 시스템
>
> **Project**: spring-event-lab
> **Version**: 0.0.1-SNAPSHOT
> **Author**: beomjin
> **Date**: 2026-04-09
> **Status**: Draft
> **Roadmap**: [flash-sale.plan.md](flash-sale.plan.md) (5/5)
> **Depends On**: 02-redis-stock, 04-kafka-consumer

---

## Executive Summary

| Perspective | Content |
|-------------|---------|
| **Problem** | 이벤트 오픈 순간 수만 명이 동시에 몰리면, 서버 자체가 과부하에 빠질 수 있음 |
| **Solution** | Redis Sorted Set으로 유저를 대기열에 세우고, 일정 속도로 입장시켜 서버 부하를 제어 |
| **Function/UX Effect** | 유저에게 "현재 N번째 대기 중" 실시간 순번을 보여주고, 순서대로 공정하게 입장 |
| **Core Value** | 서버 과부하 방지 + 사용자 경험 개선 (무한 로딩 대신 명확한 대기 순번 제공) |

---

## 1. Overview

### 1.1 Purpose

앞선 redis-stock + kafka-consumer로 발급 자체는 안전하지만, **수만 명이 동시에 API를 호출하면 서버 자체가 과부하**에 빠질 수 있다.
대기열(Waiting Queue)을 통해 동시 처리량을 제어하고, 유저에게 공정한 순서를 보장한다.

### 1.2 학습 포인트

- **Redis Sorted Set**: ZADD, ZRANK, ZPOPMIN 활용
- **Rate Limiting vs Queuing**: 요청을 거부하는 것과 줄을 세우는 것의 차이
- **Scheduler**: Spring @Scheduled로 주기적 배치 입장 처리
- **실시간 순번 조회**: ZRANK로 O(log N) 순번 확인

---

## 2. Scope

### 2.1 In Scope

- [ ] 대기열 진입 API: `POST /api/v1/events/{eventId}/enter`
- [ ] 대기 순번 조회 API: `GET /api/v1/events/{eventId}/rank?userId={userId}`
- [ ] Scheduler: N초마다 대기열에서 M명 꺼내서 발급 처리
- [ ] 대기열 상태 조회 (총 대기 인원)
- [ ] 입장 완료 시 대기열에서 제거

### 2.2 Out of Scope

- WebSocket/SSE 실시간 순번 푸시 (폴링으로 대체)
- 대기열 우선순위 차등 (VIP 등)
- 멀티 이벤트 동시 대기열

---

## 3. Requirements

### 3.1 Functional Requirements

| ID | Requirement | Priority | Status |
|----|-------------|----------|--------|
| FR-01 | 대기열 진입 API: Sorted Set에 userId 추가 (score = timestamp) | High | Pending |
| FR-02 | 순번 조회 API: ZRANK로 현재 대기 순번 반환 | High | Pending |
| FR-03 | Scheduler: 주기적으로 ZPOPMIN으로 N명 꺼내서 발급 처리 | High | Pending |
| FR-04 | 이미 대기열에 있는 유저의 중복 진입 방지 | Medium | Pending |
| FR-05 | 대기열 총 인원 조회 (ZCARD) | Medium | Pending |

---

## 4. Core Flow

```
1. 유저 → POST /api/v1/events/{eventId}/enter
2. ZADD waiting:{eventId} {timestamp} {userId}
3. → 200 OK (대기열 진입 완료, 현재 순번: N)

4. 유저 → GET /api/v1/events/{eventId}/rank?userId={userId}
5. ZRANK waiting:{eventId} {userId}
6. → 200 OK (현재 순번: M)

--- Scheduler (매 5초) ---

7. ZPOPMIN waiting:{eventId} {batchSize}
8. 팝된 유저들 → 기존 발급 로직 (Redis DECR → Kafka → DB) 실행
```

---

## 5. Redis Key Design

| Key Pattern | Type | Purpose | TTL |
|-------------|------|---------|-----|
| `waiting:{eventId}` | Sorted Set | 대기열 (score=밀리초 timestamp) | 이벤트 종료 + 1h |

---

## 6. Success Criteria

- [ ] 대기열에 진입한 순서대로 발급이 처리된다
- [ ] 동일 유저가 중복으로 대기열에 들어가지 않는다
- [ ] 순번 조회가 실시간으로 정확하다
- [ ] Scheduler가 주기적으로 대기열을 소비한다

---

## 7. Next Steps

1. [ ] Design 문서 작성
2. [ ] 구현
3. [ ] 전체 Flash Sale 시스템 통합 테스트

---

## Version History

| Version | Date | Changes | Author |
|---------|------|---------|--------|
| 0.1 | 2026-04-09 | Initial draft | beomjin |
