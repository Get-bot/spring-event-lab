---
name: Waiting Queue Feature Completion
description: Flash Sale Roadmap 5/5 완성 — Redis Sorted Set 대기열, Backpressure, 99% Match Rate
type: project
---

## Feature Completion Summary

**Feature**: 05-waiting-queue (Flash Sale Roadmap 5/5 — 최종 단계)  
**Completion Date**: 2026-04-25  
**Match Rate**: 99.0% (P0/P1: 0, P2: 3 모두 design 문서 표기 오류)  
**Duration**: 1 day (Plan 2026-04-09 + Design/Do/Check/Report 2026-04-25)  
**Status**: ✅ COMPLETED

## Why: 문제와 가치

**Problem**: redis-stock + kafka-consumer로 발급 안전성은 확보했으나, 오픈 순간 5만+ 동시 요청이 Tomcat/Redis/DB에 동시 압력 → latency 급증, 클라이언트 무한 로딩

**Solution**: Redis Sorted Set 대기열 + @Scheduled 배치 처리 + 결과 비동기 통지

**Core Value**: **Backpressure 패턴** — 거부(429) 대신 지연(200 + rank), admission rate 제어로 시스템 처리 능력과 트래픽 스파이크 분리

## How: 핵심 설계 결정

1. **Lua 원자성** (enter_queue.lua): SISMEMBER(issued) + ZSCORE(waiting) + ZADD + EXPIRE 1 RTT
2. **결과 키 패턴**: ZPOPMIN은 destructive → result:{eventId}:{userId} TTL 1h로 별도 저장
3. **SOLD_OUT short-circuit**: batch 내 첫 매진 후 Lua 호출 없이 SET만으로 일괄 통보
4. **fixedDelay**: 처리 시간 증가 시 자동 back-pressure (호출 누적 방지)
5. **기존 경로 재사용**: Scheduler → CouponIssueService.issue() (redis-stock + kafka-consumer 100% 재사용)

## What: 구현 산출물

**New 11개**: enter_queue.lua + WaitingQueueRepository + Service + Controller + Scheduler + SchedulingConfig + QueueStatus/EnterResponse/StatusResponse DTOs

**Modified 4개**: RedisConfig (enterQueueScript Bean), ErrorCode (USER_ALREADY_IN_QUEUE), EventRepository (findAllOpenAt), application.yaml (waiting-queue props)

**Lines**: ~600 (Kotlin 540 + Lua 27 + YAML 13)

## Learning Outcomes

- **L1**: Design 자기 모순 (§2.5 vs §3.8) → design-validator 필요
- **L2**: Queue ≠ Rate Limiter (거부 vs 지연의 UX 차이)
- **L3**: ZPOPMIN destructive → result 키 별도 도입 (crash 복구는 Redis Streams next)
- **L4**: 학습 프로젝트의 정직한 OOS (§11 5개 항목)
- **L5**: 적층 구조 — CRUD(DDD) → Stock(Lua) → Concurrency → Kafka → **Queue** 순서로 각각 이전 feature 재사용

## Context for Future Sessions

- Flash Sale Roadmap **완료**: 5/5 모두 달성, 누적 4,500 LOC, 6 PDCA cycles
- Convention Compliance: 6/6 = 100% (BusinessException, @Schema, findByIdOrNull, DDD, hash tags, errorcode)
- 테스트 작성은 의도적으로 deferred (design §9 명세 완성, 별도 PR에서 L2~L4)
- **Next Phase**: Redis Streams(crash 복구) / ShedLock(분산 락) / Caffeine(캐싱) / SSE(결과 푸시)
