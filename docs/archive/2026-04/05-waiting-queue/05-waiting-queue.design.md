# Waiting Queue Design Document

> **Summary**: 발급 API 앞단에 Redis Sorted Set 대기열을 두고, `@Scheduled` 워커가 일정 속도로 dequeue하여 기존 `CouponIssueService.issue()`를 호출. 결과는 `result:{eventId}:{userId}` 키로 비동기 통지
>
> **Project**: spring-event-lab
> **Version**: 0.0.1-SNAPSHOT
> **Author**: beomjin
> **Date**: 2026-04-25
> **Status**: Draft
> **Planning Doc**: [05-waiting-queue.plan.md](../../01-plan/features/05-waiting-queue.plan.md)
> **Depends On**: 02-redis-stock (`RedisStockRepository`, Lua 스크립트 패턴), 04-kafka-consumer (`CouponIssueService`, `CouponIssueProducer`)

---

## Executive Summary

| Perspective | Content |
|-------------|---------|
| **Problem** | redis-stock + kafka-consumer로 발급 자체는 안전하지만, 오픈 순간 5만+ 동시 요청이 Tomcat 워커·Redis 핫키·DB 풀에 동시 압력을 가해 latency가 무너지고 클라이언트는 무한 로딩에 빠짐 |
| **Solution** | `POST /enter` → Redis Sorted Set `waiting:{eventId}`에 ZADD(score=arrivalMs)로 즉시 줄 세우고, `@Scheduled` 워커가 N초마다 ZPOPMIN로 M명을 꺼내 기존 `CouponIssueService.issue()`를 호출. 결과는 `result:{eventId}:{userId}` 키에 기록되어 클라이언트가 폴링 |
| **Function/UX Effect** | 유저에게 즉시 200 + `{status: WAITING, rank: 1234}` 응답. 폴링으로 `WAITING → ISSUED|SOLD_OUT` 전이를 관찰. 무한 로딩 대신 진행 상황 가시화 |
| **Core Value** | 시스템의 처리 능력(DB TPS, Redis Lua 처리량)과 트래픽 스파이크를 분리하는 **Backpressure 패턴**을 학습. 입장률을 `batch-size / poll-interval`로 운영자가 제어 가능 |

---

## 1. Overview

### 1.1 Design Goals

- **Backpressure 도입**: 발급 처리 속도 ≥ 입장 속도가 되도록 Scheduler가 admission rate를 제어
- **공정성 보장**: arrival timestamp(ms)를 score로 사용해 FCFS — 동일 ms 충돌 시 userId 사전순으로 안정적
- **응답 즉시성 유지**: `POST /enter`는 Redis Lua 1 RTT만 사용 (DB는 시간 검증용 1회 SELECT). p99 ≤ 50ms 목표
- **결과 통지의 명확성**: ZPOPMIN은 destructive하므로 결과를 별도 키로 영속화. 유저가 `WAITING / ISSUED / SOLD_OUT / ALREADY_ISSUED / FAILED` 상태 전이를 관찰 가능
- **기존 발급 경로 재사용**: Scheduler가 `CouponIssueService.issue()`를 직접 호출 — Lua 스크립트, Producer 보상, Kafka Consumer까지 그대로 동작. **신규 경로는 enter/scheduler/result 3개**만 추가

### 1.2 Design Principles

- **Queue ≠ Rate Limiter**: 거부(429)가 아니라 지연(200 + rank). 유저에게 ETA를 제공하고, 시스템에는 평탄한 입력 곡선을 제공
- **Lua로 enter의 원자성 보장**: 「이미 발급됨 / 이미 큐에 있음 / 신규 enqueue」를 1 RTT 안에서 결정 — race condition 차단
- **Scheduler는 thin orchestrator**: dequeue → 기존 service.issue() 호출 → 결과 기록만. 비즈니스 로직 추가 금지
- **결과 키는 short-lived**: `result:{eventId}:{userId}` TTL 1h — 유저는 즉시 폴링하므로 길지 않아도 충분. Redis 메모리 압박 방지
- **장애 격리**: Scheduler가 한 유저 처리 중 실패해도 batch 내 다음 유저는 계속 처리. 실패는 result 키에 `FAILED:<reason>`로 기록

---

## 2. Architecture

### 2.1 Component Diagram

```
┌──────────────┐     ┌─────────────────────────────────────────────────────────┐     ┌──────────────┐
│              │     │                   Spring Boot App                       │     │              │
│   Client     │────▶│  WaitingQueueController        (NEW)                   │     │              │
│ (Swagger UI) │     │      ↓                                                  │     │              │
│              │     │  WaitingQueueService           (NEW)                    │     │   Redis 8    │
│              │     │      ↓                                                  │────▶│              │
│              │     │  WaitingQueueRepository        (NEW)                    │     │  Sorted Set  │
│              │     │   - enter_queue.lua  (Lua EVAL)                         │     │   String     │
│              │     │   - ZRANK / ZCARD / ZPOPMIN                             │     │              │
│              │     │                                                         │     └──────────────┘
│              │     │  ┌──────────────────────────────────────────────────┐   │
│              │     │  │  CouponIssueScheduler          (NEW)              │   │     ┌──────────────┐
│              │     │  │  @Scheduled(fixedDelay=1s)                        │   │     │   Kafka      │
│              │     │  │   ↓                                               │   │     │              │
│              │     │  │  EventRepository.findOpenAt(now)                  │   │     └──────────────┘
│              │     │  │   ↓                                               │   │     ┌──────────────┐
│              │     │  │  WaitingQueueRepository.popMin(eventId, batch)    │   │     │  PostgreSQL  │
│              │     │  │   ↓                                               │   │     │              │
│              │     │  │  for each userId:                                 │   │     └──────────────┘
│              │     │  │    CouponIssueService.issue(eventId, userId)     │   │
│              │     │  │      ↓ (기존 redis-stock + kafka-consumer 경로)   │   │
│              │     │  │    WaitingQueueRepository.recordResult(...)       │   │
│              │     │  └──────────────────────────────────────────────────┘   │
│              │◀────│  GlobalExceptionHandler                                 │
│              │     └─────────────────────────────────────────────────────────┘
└──────────────┘
```

### 2.2 Data Flow — Enter (동기 구간)

```
POST /api/v1/events/{eventId}/enter?userId={userId}

1. [Controller] WaitingQueueController.enter(eventId, userId)

2. [Service] 이벤트 조회 + 시간 검증
   eventRepository.findByIdOrNull(eventId)
   → event.period.contains(Instant.now())
   └─ false → EVENT_NOT_OPEN (409)

3. [Service → Repository] Lua EVAL enter_queue.lua
   KEYS = [waiting:{eventId}, coupon:issued:{eventId}]
   ARGV = [userId, scoreMs, ttlSeconds]
   ├─ -1 → COUPON_ALREADY_ISSUED (409, redis-stock issued Set에 이미 존재)
   ├─  0 → USER_ALREADY_IN_QUEUE (409)
   └─  1 → 신규 enqueue 성공

4. [Service → Repository] ZRANK + ZCARD
   rank = ZRANK waiting:{eventId} {userId}    (0-based → +1)
   total = ZCARD waiting:{eventId}

5. → 200 OK { status: WAITING, rank, totalWaiting }
```

### 2.3 Data Flow — Status 폴링

```
GET /api/v1/events/{eventId}/queue/status?userId={userId}

1. [Controller] WaitingQueueController.status(eventId, userId)

2. [Service] 결과 키 우선 조회
   GET result:{eventId}:{userId}
   ├─ "ISSUED:{couponIssueId}"        → { status: ISSUED, issueId }
   ├─ "SOLD_OUT"                       → { status: SOLD_OUT }
   ├─ "ALREADY_ISSUED"                 → { status: ALREADY_ISSUED }
   ├─ "FAILED:{errorCode}"             → { status: FAILED, reason }
   └─ nil → 다음 단계

3. [Service] 큐에 있는지 확인
   rank = ZRANK waiting:{eventId} {userId}
   ├─ rank ≠ null → { status: WAITING, rank+1, totalWaiting=ZCARD }
   └─ rank = null → { status: NOT_IN_QUEUE }   (TTL 만료/입장 안 함)

4. → 200 OK { ... }
```

### 2.4 Data Flow — Scheduler (백그라운드)

```
[@Scheduled fixedDelay=1s]

1. [Scheduler] 현재 시각에 진행 중인 이벤트 조회
   eventRepository.findAllByEventStatusAndPeriodContaining(OPEN, now)
   → 보통 1~수개

2. for each openEvent:
   waitingQueueRepository.popMin(openEvent.id, batchSize=100)
     → ZPOPMIN waiting:{eventId} 100
     → List<UUID>

   for each userId in popped:
     try:
       response = couponIssueService.issue(eventId, userId)
       waitingQueueRepository.recordResult(eventId, userId, "ISSUED:${response.id}")
     catch BusinessException(EVENT_SOLD_OUT):
       recordResult(..., "SOLD_OUT")
       break  // 이후 유저는 모두 SOLD_OUT — 빠른 short-circuit (3.6 참조)
     catch BusinessException(COUPON_ALREADY_ISSUED):
       recordResult(..., "ALREADY_ISSUED")
     catch BusinessException(other):
       recordResult(..., "FAILED:${errorCode.name}")
     catch Exception(unexpected):
       log.error + recordResult(..., "FAILED:UNKNOWN")
       // 다음 유저는 계속 처리 (개별 격리)
```

**중요 — break 로직**: 한 batch 안에서 첫 SOLD_OUT 이후 남은 user들은 모두 같은 결과를 받게 된다. 이 경우 `break`로 batch 처리를 중단하고 남은 유저를 큐로 되돌리지 않는다 — 어차피 매진이므로 이번 tick에 빠르게 결과 통보하는 게 나음. **다음 tick부터는 SOLD_OUT이 즉시 응답되고 큐는 빠르게 비워짐**.

### 2.5 Dependencies

| 신규/변경 컴포넌트 | 의존하는 기존 컴포넌트 | 용도 |
|--------------------|----------------------|------|
| `WaitingQueueController` (NEW) | `WaitingQueueService` | HTTP 매핑 (`/enter`, `/queue/status`) |
| `WaitingQueueService` (NEW) | `EventRepository`, `WaitingQueueRepository` | 이벤트 검증 + 큐 조작 |
| `WaitingQueueRepository` (NEW) | `StringRedisTemplate`, `RedisScript<Long>` | ZADD/ZRANK/ZCARD/ZPOPMIN + Lua EVAL |
| `CouponIssueScheduler` (NEW) | `EventRepository`, `WaitingQueueRepository`, `CouponIssueService` (기존 그대로) | 주기적 dequeue + issue 호출 |
| `enter_queue.lua` (NEW) | — | enter 원자성 (이미 발급/이미 큐 검사 + ZADD + EXPIRE) |
| `RedisConfig` (MODIFY) | `ClassPathResource` | `enterQueueScript` Bean 추가 |
| `SchedulingConfig` (NEW) | — | `@EnableScheduling` + `ThreadPoolTaskScheduler` |
| `EventRepository` (MODIFY) | — | `findAllByEventStatusAndStartedAtBeforeAndEndedAtAfter` 메서드 추가 |
| `ErrorCode` (MODIFY) | — | `USER_ALREADY_IN_QUEUE` 추가 |
| `CouponIssueService` (NO CHANGE) | — | scheduler가 그대로 호출 |
| `CouponIssueController.issue` (NO CHANGE) | — | 직접 호출 경로 유지 (학습용 비교) — § 11.2 참조 |

---

## 3. Detailed Design

### 3.1 Redis Key Design

| Key | Type | Purpose | TTL |
|-----|------|---------|-----|
| `waiting:{eventId}` | Sorted Set | 대기열 (member=userId, score=arrivalMs) | event.endedAt + 1h |
| `result:{eventId}:{userId}` | String | dequeue 후 발급 결과 페이로드 | 1h (`waiting-queue.result-ttl-seconds`) |
| `coupon:stock:{eventId}` | String (기존) | 재고 카운터 | event.endedAt + 1h |
| `coupon:issued:{eventId}` | Set (기존) | 발급된 userId | event.endedAt + 1h |

**Key 네이밍 — `{eventId}` 해시 슬롯**: redis-stock 이미 `{...}` 해시 태그를 사용해 같은 eventId의 키들이 동일 슬롯에 위치하도록 했음. waiting/result 키도 동일 패턴 → 향후 Redis Cluster 전환 시 한 슬롯 안에서 EVAL/MULTI 가능.

```
waiting:{<eventId>}
result:{<eventId>}:<userId>
```

> **결정**: `result` 키는 multi-key Lua 대상이 아니므로 해시 태그 필수는 아니지만, 일관성을 위해 동일 패턴 유지. 단 `userId` 부분은 해시 태그 밖.

### 3.2 enter_queue.lua

```lua
-- enter_queue.lua
-- KEYS[1] = waiting:{eventId}
-- KEYS[2] = coupon:issued:{eventId}
-- ARGV[1] = userId
-- ARGV[2] = score (arrival timestamp, ms)
-- ARGV[3] = ttlSeconds

-- 1) 이미 발급된 유저인지 (재진입 차단)
if redis.call('SISMEMBER', KEYS[2], ARGV[1]) == 1 then
    return -1
end

-- 2) 이미 대기열에 있는지 (중복 enter 차단)
if redis.call('ZSCORE', KEYS[1], ARGV[1]) ~= false then
    return 0
end

-- 3) ZADD + 최초 진입 시 TTL 설정
redis.call('ZADD', KEYS[1], ARGV[2], ARGV[1])
if redis.call('TTL', KEYS[1]) < 0 then
    redis.call('EXPIRE', KEYS[1], ARGV[3])
end

return 1
```

**설계 결정 — issue_coupon.lua와의 일관성**:
- 반환값 의미가 동일 패턴: `1=성공`, `0=중복(소프트 거부)`, `-1=강한 거부`
- redis-stock과 동일하게 `IssueResult` 같은 enum으로 매핑 (3.4 참조 — `EnterResult`)

**설계 결정 — `ZADD` (no `NX`)**:
- ZADD에 `NX` 옵션을 줘서 "기존에 있으면 무시"로 처리할 수도 있지만, 그러면 "이미 큐에 있음"과 "신규 추가"를 호출자가 구별 못 함 (`ZADD NX`는 추가된 개수를 반환)
- ZSCORE로 명시적 체크 후 ZADD → 의도가 명확하고 race도 Lua atomic 안에서 차단

**설계 결정 — score 클럭**:
- `Instant.now().toEpochMilli()`를 ms 단위로 사용 → 충돌 시 같은 score 유저는 ZRANGE 정렬상 member 사전순 (Redis 스펙)
- ms 해상도면 충분 (Sorted Set 안정 정렬). 더 정밀하려면 `System.nanoTime()`도 가능하나 ms로 학습 예제 단순화

### 3.3 RedisConfig (MODIFY)

```kotlin
@Configuration
class RedisConfig {
    @Bean
    fun issueCouponScript(): RedisScript<Long> =
        RedisScript.of(ClassPathResource("scripts/issue_coupon.lua"), Long::class.java)

    @Bean
    fun enterQueueScript(): RedisScript<Long> =
        RedisScript.of(ClassPathResource("scripts/enter_queue.lua"), Long::class.java)
}
```

### 3.4 WaitingQueueRepository

```kotlin
package com.beomjin.springeventlab.coupon.repository

@Repository
class WaitingQueueRepository(
    private val redisTemplate: StringRedisTemplate,
    private val enterQueueScript: RedisScript<Long>,
) {
    private fun waitingKey(eventId: UUID): String = "waiting:{$eventId}"
    private fun resultKey(eventId: UUID, userId: UUID): String = "result:{$eventId}:$userId"

    /**
     * Lua EVAL — 발급/큐 중복 체크 + ZADD + EXPIRE를 1 RTT에 처리.
     * @return EnterResult.SUCCESS / ALREADY_IN_QUEUE / ALREADY_ISSUED
     */
    fun tryEnter(
        eventId: UUID,
        userId: UUID,
        scoreMs: Long,
        ttlSeconds: Long,
    ): EnterResult {
        val code = redisTemplate.execute(
            enterQueueScript,
            listOf(waitingKey(eventId), "coupon:issued:{$eventId}"),
            userId.toString(),
            scoreMs.toString(),
            ttlSeconds.toString(),
        ) ?: throw IllegalStateException("Lua script returned null")
        return EnterResult.fromCode(code)
    }

    /** ZRANK 0-based → +1로 1-based 표기 (UI 친화) */
    fun rank(eventId: UUID, userId: UUID): Long? =
        redisTemplate.opsForZSet()
            .rank(waitingKey(eventId), userId.toString())
            ?.let { it + 1 }

    fun size(eventId: UUID): Long =
        redisTemplate.opsForZSet().size(waitingKey(eventId)) ?: 0L

    /**
     * ZPOPMIN으로 score가 작은(=먼저 진입한) 유저 N명을 원자적으로 제거하며 반환.
     */
    fun popMin(eventId: UUID, count: Long): List<UUID> {
        val popped = redisTemplate.opsForZSet().popMin(waitingKey(eventId), count) ?: return emptyList()
        return popped.mapNotNull { it.value?.let(UUID::fromString) }
    }

    fun recordResult(eventId: UUID, userId: UUID, payload: String, ttl: Duration) {
        redisTemplate.opsForValue().set(resultKey(eventId, userId), payload, ttl)
    }

    fun findResult(eventId: UUID, userId: UUID): String? =
        redisTemplate.opsForValue().get(resultKey(eventId, userId))
}

enum class EnterResult(val code: Long) {
    ALREADY_ISSUED(-1),
    ALREADY_IN_QUEUE(0),
    SUCCESS(1);

    companion object {
        fun fromCode(code: Long): EnterResult =
            entries.find { it.code == code }
                ?: throw IllegalStateException("Unknown EnterResult code=$code")
    }
}
```

**설계 결정 — `ZPOPMIN` 사용**:
- `ZPOPMIN waiting:{eventId} N`은 Redis 5.0+에서 atomic. List `RPOP` count 옵션 대비 score 기반 정렬 보장
- Spring Data Redis의 `ZSetOperations.popMin(key, count)` 시그니처 사용 — `Set<TypedTuple<String>>` 반환, 순서 보존 (LinkedHashSet)
- **Trade-off**: ZPOPMIN은 destructive — 처리 중 앱 크래시 시 popped 유저는 큐에서도 result 키에서도 사라져 영구 미발급 상태가 된다. § 11.1에서 추가 논의

### 3.5 WaitingQueueService

```kotlin
package com.beomjin.springeventlab.coupon.service

@Service
class WaitingQueueService(
    private val eventRepository: EventRepository,
    private val waitingQueueRepository: WaitingQueueRepository,
    private val waitingQueueProperties: WaitingQueueProperties,
) {
    fun enter(eventId: UUID, userId: UUID): QueueEnterResponse {
        val event = eventRepository.findByIdOrNull(eventId)
            ?: throw BusinessException(ErrorCode.EVENT_NOT_FOUND)

        val now = Instant.now()
        if (!event.period.contains(now)) {
            throw BusinessException(ErrorCode.EVENT_NOT_OPEN)
        }

        val ttlSeconds = Duration.between(now, event.period.endedAt).plusHours(1).toSeconds()

        when (waitingQueueRepository.tryEnter(eventId, userId, now.toEpochMilli(), ttlSeconds)) {
            EnterResult.ALREADY_ISSUED -> throw BusinessException(ErrorCode.COUPON_ALREADY_ISSUED)
            EnterResult.ALREADY_IN_QUEUE -> throw BusinessException(ErrorCode.USER_ALREADY_IN_QUEUE)
            EnterResult.SUCCESS -> Unit
        }

        return QueueEnterResponse(
            status = QueueStatus.WAITING,
            rank = waitingQueueRepository.rank(eventId, userId),
            totalWaiting = waitingQueueRepository.size(eventId),
        )
    }

    fun status(eventId: UUID, userId: UUID): QueueStatusResponse {
        // 결과가 있으면 결과 우선 — dequeue 이후 상태
        waitingQueueRepository.findResult(eventId, userId)?.let { payload ->
            return QueueStatusResponse.fromResultPayload(payload)
        }

        // 결과 없음 → 큐 잔존 여부 확인
        val rank = waitingQueueRepository.rank(eventId, userId)
        return if (rank != null) {
            QueueStatusResponse(
                status = QueueStatus.WAITING,
                rank = rank,
                totalWaiting = waitingQueueRepository.size(eventId),
            )
        } else {
            QueueStatusResponse(status = QueueStatus.NOT_IN_QUEUE)
        }
    }
}
```

**설계 결정 — enter에서 issued 체크가 두 곳**:
- Lua가 `coupon:issued:{eventId}` SISMEMBER로 1차 차단 → 빠른 거부 + race 차단
- 그래도 enter와 dequeue 사이에 발급이 진행될 수 있어 service.issue() 안의 Lua가 최종 방어선
- 즉 **두 번 체크되는 것이 정상** — Lua 안의 단일 EVAL은 race-free하지만 enter→dequeue→issue는 여러 EVAL 사이에 시간 간격 존재

### 3.6 CouponIssueScheduler

```kotlin
package com.beomjin.springeventlab.coupon.scheduler

@Component
class CouponIssueScheduler(
    private val eventRepository: EventRepository,
    private val waitingQueueRepository: WaitingQueueRepository,
    private val couponIssueService: CouponIssueService,
    private val waitingQueueProperties: WaitingQueueProperties,
) {
    @Scheduled(fixedDelayString = "\${waiting-queue.poll-interval-ms:1000}")
    fun drainQueues() {
        val now = Instant.now()
        // EventStatus.OPEN + period 시간 내인 이벤트만 처리
        val openEvents = eventRepository.findAllOpenAt(now)
        if (openEvents.isEmpty()) return

        for (event in openEvents) {
            drainOneEvent(event.id)
        }
    }

    private fun drainOneEvent(eventId: UUID) {
        val poppedUserIds = waitingQueueRepository.popMin(eventId, waitingQueueProperties.batchSize.toLong())
        if (poppedUserIds.isEmpty()) return

        val resultTtl = Duration.ofSeconds(waitingQueueProperties.resultTtlSeconds)

        for (userId in poppedUserIds) {
            try {
                val response = couponIssueService.issue(eventId, userId)
                waitingQueueRepository.recordResult(
                    eventId, userId, "ISSUED:${response.id}", resultTtl,
                )
            } catch (e: BusinessException) {
                handleBusinessException(eventId, userId, e, resultTtl)
                if (e.errorCode == ErrorCode.EVENT_SOLD_OUT) {
                    // 이후 batch 잔여 유저는 어차피 같은 결과 — 빠른 통보 후 짧게 종료
                    drainRemainingAsSoldOut(eventId, poppedUserIds, userId, resultTtl)
                    return
                }
            } catch (e: Exception) {
                log.error(e) { "Unexpected drain failure — eventId=$eventId userId=$userId" }
                waitingQueueRepository.recordResult(eventId, userId, "FAILED:UNKNOWN", resultTtl)
            }
        }
    }

    private fun handleBusinessException(
        eventId: UUID, userId: UUID, e: BusinessException, ttl: Duration,
    ) {
        val payload = when (e.errorCode) {
            ErrorCode.EVENT_SOLD_OUT -> "SOLD_OUT"
            ErrorCode.COUPON_ALREADY_ISSUED -> "ALREADY_ISSUED"
            else -> "FAILED:${e.errorCode.name}"
        }
        waitingQueueRepository.recordResult(eventId, userId, payload, ttl)
    }

    private fun drainRemainingAsSoldOut(
        eventId: UUID, popped: List<UUID>, currentUserId: UUID, ttl: Duration,
    ) {
        val idx = popped.indexOf(currentUserId)
        if (idx < 0 || idx == popped.lastIndex) return
        for (userId in popped.subList(idx + 1, popped.size)) {
            waitingQueueRepository.recordResult(eventId, userId, "SOLD_OUT", ttl)
        }
    }

    companion object {
        private val log = KotlinLogging.logger {}
    }
}
```

**설계 결정 — `fixedDelay` (vs `fixedRate`)**:
- `fixedDelay`: 이전 실행 종료 + N ms 후 다음 실행 → 처리 시간이 길어지면 자동으로 간격이 늘어남 (back-pressure 친화)
- `fixedRate`: N ms 마다 무조건 실행 → 처리 시간이 N ms를 넘기면 호출이 누적됨 (overload 위험)
- 학습 프로젝트는 안전한 `fixedDelay`. 처리 lag을 메트릭으로 관찰하기에도 좋음

**설계 결정 — SOLD_OUT short-circuit**:
- 한 batch 안에서 첫 SOLD_OUT 발생 시, 남은 popped 유저는 어차피 매진. 일일이 issue() 호출하면 Redis Lua만 낭비
- 남은 유저에게 즉시 SOLD_OUT 결과 기록 → 다음 폴링에서 즉시 안내
- **잔여 큐 (아직 ZPOPMIN되지 않은 유저)**: 다음 tick에 popMin → issue() → 첫 호출에서 SOLD_OUT → 다시 short-circuit. 즉 batch_size만큼만 헛 EVAL이 발생하고 큐는 빠르게 비워짐

**설계 결정 — `EventRepository.findAllOpenAt`**:
- 매 tick마다 DB 조회 1회 (이벤트 수개 조회). 캐싱 없음 — OPEN 이벤트는 수개 정도이며 PG는 인덱스 hit으로 sub-ms
- 향후 다수 이벤트 동시 운영 시 `@Cacheable` (Caffeine, 5초 TTL) 권장 — § 11.1 참조

**설계 결정 — Scheduler 단일 인스턴스 가정**:
- 다중 인스턴스 배포 시 각 인스턴스가 동시에 ZPOPMIN을 호출 → 정상 동작 (atomic). 단 `findAllOpenAt`은 DB 동시 조회로 부하 약간 증가
- 분산 락(ShedLock 등)은 추가하지 않음 — ZPOPMIN의 atomicity로 중복 발급은 발생 안 함. 단 입장률이 인스턴스 수만큼 곱해짐 (의도된 결과로 볼 수도 있음)
- § 11.1에서 운영 권고

### 3.7 WaitingQueueController + DTOs

```kotlin
package com.beomjin.springeventlab.coupon.controller

@RestController
@RequestMapping("/api/v1/events")
@Tag(name = "Waiting Queue", description = "쿠폰 발급 대기열 API")
class WaitingQueueController(
    private val waitingQueueService: WaitingQueueService,
) {
    @PostMapping("/{eventId}/enter")
    @Operation(summary = "대기열 진입", description = "Sorted Set에 userId를 ZADD하고 현재 순번을 반환합니다.")
    fun enter(
        @PathVariable eventId: UUID,
        @RequestParam userId: UUID,
    ): ResponseEntity<QueueEnterResponse> =
        ResponseEntity.ok(waitingQueueService.enter(eventId, userId))

    @GetMapping("/{eventId}/queue/status")
    @Operation(summary = "대기열 상태 조회", description = "현재 순번 또는 발급 결과를 조회합니다.")
    fun status(
        @PathVariable eventId: UUID,
        @RequestParam userId: UUID,
    ): ResponseEntity<QueueStatusResponse> =
        ResponseEntity.ok(waitingQueueService.status(eventId, userId))
}
```

```kotlin
package com.beomjin.springeventlab.coupon.dto.response

@Schema(description = "대기열 상태")
enum class QueueStatus {
    WAITING,         // 큐 안에서 대기 중
    ISSUED,          // 발급 성공
    SOLD_OUT,        // 매진
    ALREADY_ISSUED,  // 이미 발급됨 (이전 batch에서 처리됨)
    FAILED,          // 기타 실패 (errorCode 참조)
    NOT_IN_QUEUE,    // 큐에도 없고 결과도 없음 (TTL 만료)
}

@Schema(description = "대기열 진입 응답")
data class QueueEnterResponse(
    @Schema(description = "현재 상태", example = "WAITING")
    val status: QueueStatus,
    @Schema(description = "현재 순번 (1-based)", example = "1234")
    val rank: Long?,
    @Schema(description = "총 대기 인원", example = "5000")
    val totalWaiting: Long?,
)

@Schema(description = "대기열 상태 조회 응답")
data class QueueStatusResponse(
    val status: QueueStatus,
    @Schema(description = "WAITING일 때만 채워짐") val rank: Long? = null,
    @Schema(description = "WAITING일 때만 채워짐") val totalWaiting: Long? = null,
    @Schema(description = "ISSUED일 때만 채워짐") val issueId: UUID? = null,
    @Schema(description = "FAILED일 때만 채워짐") val reason: String? = null,
) {
    companion object {
        fun fromResultPayload(payload: String): QueueStatusResponse {
            val (head, tail) = payload.substringBefore(':') to payload.substringAfter(':', "")
            return when (head) {
                "ISSUED" -> QueueStatusResponse(QueueStatus.ISSUED, issueId = UUID.fromString(tail))
                "SOLD_OUT" -> QueueStatusResponse(QueueStatus.SOLD_OUT)
                "ALREADY_ISSUED" -> QueueStatusResponse(QueueStatus.ALREADY_ISSUED)
                "FAILED" -> QueueStatusResponse(QueueStatus.FAILED, reason = tail)
                else -> QueueStatusResponse(QueueStatus.FAILED, reason = "UNKNOWN_PAYLOAD")
            }
        }
    }
}
```

**설계 결정 — payload 직렬화 형식**:
- `result:` 키 값은 단순 String. JSON으로 둘 수도 있지만 학습 예제는 `KEY:VALUE` 콜론 형식이 더 읽기 쉬움 (redis-cli로 GET 시 사람-친화)
- 향후 필드가 늘면 JSON 전환 고려 (Jackson + `@JsonValue` enum)

### 3.8 EventRepository — 신규 메서드

```kotlin
interface EventRepository : JpaRepository<Event, UUID>, EventQueryRepository {

    /**
     * 현재 시각이 period 내이고 status=OPEN인 이벤트 — Scheduler가 매 tick 호출.
     */
    @Query("""
        SELECT e FROM Event e
        WHERE e.eventStatus = com.beomjin.springeventlab.coupon.entity.EventStatus.OPEN
          AND e.period.startedAt <= :now
          AND e.period.endedAt > :now
    """)
    fun findAllOpenAt(@Param("now") now: Instant): List<Event>
}
```

**설계 결정 — JPQL `:now`**:
- `period.startedAt`/`period.endedAt`는 `@Embedded DateRange`의 컬럼이므로 JPQL에서 `e.period.startedAt`로 접근 가능
- 메서드 이름 derivation은 `@Embedded` 필드 + 두 조건 + 대소 조합으로 가독성 떨어져 명시적 `@Query` 선택

### 3.9 SchedulingConfig (NEW)

```kotlin
package com.beomjin.springeventlab.global.config

@Configuration
@EnableScheduling
class SchedulingConfig {
    @Bean
    fun taskScheduler(): ThreadPoolTaskScheduler =
        ThreadPoolTaskScheduler().apply {
            poolSize = 2
            setThreadNamePrefix("scheduler-")
            setWaitForTasksToCompleteOnShutdown(true)
            setAwaitTerminationSeconds(10)
            initialize()
        }
}
```

**설계 결정 — `poolSize = 2`**:
- 현재 `@Scheduled` 메서드는 `drainQueues` 1개. pool size 1로도 충분
- 추후 추가 스케줄러 가능성 + graceful overlap 흡수 위해 2로 설정
- `setWaitForTasksToCompleteOnShutdown(true)` — Spring shutdown 시 진행 중 batch 끝까지 처리 (ZPOPMIN 후 처리 누락 방지)

### 3.10 ErrorCode 추가

```kotlin
// Queue Errors
USER_ALREADY_IN_QUEUE(HttpStatus.CONFLICT, "Q409-1", "이미 대기열에 등록된 유저입니다."),
```

기존 `COUPON_ALREADY_ISSUED`, `EVENT_NOT_FOUND`, `EVENT_NOT_OPEN`은 재활용.

### 3.11 application.yaml 추가

```yaml
waiting-queue:
  poll-interval-ms: 1000
  batch-size: 100
  result-ttl-seconds: 3600
```

```kotlin
@ConfigurationProperties(prefix = "waiting-queue")
data class WaitingQueueProperties(
    val pollIntervalMs: Long = 1000,
    val batchSize: Int = 100,
    val resultTtlSeconds: Long = 3600,
)
```

`@EnableConfigurationProperties(WaitingQueueProperties::class)`를 `SchedulingConfig`에 부착.

---

## 4. Sequence Diagrams

### 4.1 정상 흐름 (Enter → Polling → Issued)

```
Client          Controller        Service          Redis           Scheduler          Service.issue
  │                │                │                │                │                    │
  │ POST /enter    │                │                │                │                    │
  │───────────────▶│  enter()       │                │                │                    │
  │                │───────────────▶│  EVAL enter.lua│                │                    │
  │                │                │───────────────▶│ ZADD + EXPIRE  │                    │
  │                │                │◀──── 1 ────────│                │                    │
  │                │                │  ZRANK / ZCARD │                │                    │
  │                │                │───────────────▶│                │                    │
  │                │                │◀──── 1234/5000 │                │                    │
  │ 200 WAITING/1234◀──────────────│                │                │                    │
  │                │                │                │                │                    │
  │ GET /status    │                │                │                │                    │
  │───────────────▶│  status()      │                │                │                    │
  │                │───────────────▶│ GET result key │                │                    │
  │                │                │───────────────▶│ (nil)          │                    │
  │                │                │ ZRANK          │                │                    │
  │                │                │───────────────▶│                │                    │
  │ 200 WAITING/1234◀──────────────│                │                │                    │
  │                │                │                │                │                    │
  │                │                │                │            [tick 1s]                │
  │                │                │                │ ZPOPMIN(eId, 100)                   │
  │                │                │                │◀───────────────│                    │
  │                │                │                │  for u: issue(eId, u) ─────────────▶│
  │                │                │                │                │  Lua + Kafka send  │
  │                │                │                │                │  CouponIssueResp   │
  │                │                │                │ SET result:eId:u "ISSUED:<id>"      │
  │                │                │                │◀───────────────│                    │
  │                │                │                │                │                    │
  │ GET /status    │                │                │                │                    │
  │───────────────▶│                │                │                │                    │
  │                │                │ GET result key │                │                    │
  │                │                │───────────────▶│ "ISSUED:<id>"  │                    │
  │ 200 ISSUED/issueId◀────────────│                │                │                    │
```

### 4.2 SOLD_OUT short-circuit

```
Scheduler       Service.issue (Lua)        Redis
   │                │                       │
   │ popMin(eId,100)│                       │
   │──────────────▶│                       │
   │ [u1..u100]     │                       │
   │                │                       │
   │ issue(eId, u1)─▶ EVAL issue.lua        │
   │                │──────────────────────▶│
   │                │◀── 1 (success) ───────│
   │ 결과 ISSUED 기록 │                       │
   │                │                       │
   │ issue(eId, u2)─▶ EVAL issue.lua        │
   │                │──────────────────────▶│
   │                │◀── 0 (sold out) ──────│
   │   throws EVENT_SOLD_OUT                │
   │ 결과 SOLD_OUT 기록                       │
   │                                        │
   │ short-circuit: u3..u100 모두 SOLD_OUT 기록│
   │   (Redis SET만 호출, Lua 미호출)         │
   │                                        │
   │ return                                 │
```

---

## 5. Error Handling Matrix

| 실패 지점 | Redis 상태 | 큐 상태 | 결과 키 | HTTP 응답 | 비고 |
|-----------|-----------|--------|--------|----------|------|
| `/enter` Event 미존재 | — | 변경 없음 | — | 404 EVENT_NOT_FOUND | DB 조회 단계 |
| `/enter` 이벤트 시간 외 | — | 변경 없음 | — | 409 EVENT_NOT_OPEN | period.contains 실패 |
| `/enter` Lua -1 (이미 발급) | 변경 없음 | 변경 없음 | — | 409 COUPON_ALREADY_ISSUED | issued Set hit |
| `/enter` Lua 0 (이미 큐) | 변경 없음 | 변경 없음 | — | 409 USER_ALREADY_IN_QUEUE | ZSCORE non-null |
| `/enter` Lua 1 | 변경 없음 | ZADD 성공 | — | 200 WAITING + rank | 정상 |
| Scheduler ZPOPMIN 후 issue 성공 | DECR + SADD | 제거됨 | `ISSUED:<id>` | 폴링 시 200 ISSUED | 정상 |
| Scheduler issue → SOLD_OUT | 변경 없음 | 제거됨 | `SOLD_OUT` | 폴링 시 200 SOLD_OUT | batch 잔여 short-circuit |
| Scheduler issue → ALREADY_ISSUED | 변경 없음 | 제거됨 | `ALREADY_ISSUED` | 폴링 시 200 ALREADY_ISSUED | race 시 |
| Scheduler issue → KafkaPublishFailed | DECR 보상됨 (Producer가 SREM+INCR) | 제거됨 | `FAILED:COUPON_PUBLISH_FAILED` | 폴링 시 200 FAILED | Producer 보상 발동 |
| Scheduler 알 수 없는 예외 | 불확정 | 제거됨 | `FAILED:UNKNOWN` | 폴링 시 200 FAILED | log.error 발생 |
| Scheduler crash (ZPOPMIN 후) | 불확정 | 제거됨 | 미기록 | 폴링 시 NOT_IN_QUEUE (TTL 후) | **데이터 손실** § 11.1 |
| `/queue/status` 큐도 결과도 없음 | — | — | — | 200 NOT_IN_QUEUE | TTL 만료 또는 미진입 |

**불변식**: `/enter`로 ZADD 성공한 유저는 다음 중 하나에 도달한다 — (a) 결과 키에 ISSUED/SOLD_OUT/ALREADY_ISSUED/FAILED 중 하나 기록, (b) 큐에서 ZPOPMIN되어 처리 중, (c) Scheduler crash로 영구 미반영. (c)를 0에 수렴시키려면 § 11.1의 보상 큐 패턴 필요.

---

## 6. File Structure

```
src/main/
├── kotlin/com/beomjin/springeventlab/
│   ├── coupon/
│   │   ├── controller/
│   │   │   ├── CouponIssueController.kt          ← NO CHANGE (학습용 직접 호출 경로 유지)
│   │   │   └── WaitingQueueController.kt          ← NEW
│   │   ├── dto/
│   │   │   └── response/
│   │   │       ├── QueueEnterResponse.kt          ← NEW
│   │   │       ├── QueueStatusResponse.kt         ← NEW
│   │   │       └── QueueStatus.kt                 ← NEW (enum)
│   │   ├── repository/
│   │   │   ├── EventRepository.kt                 ← MODIFY (findAllOpenAt 추가)
│   │   │   └── WaitingQueueRepository.kt          ← NEW (+ EnterResult enum)
│   │   ├── scheduler/                             ← NEW 패키지
│   │   │   └── CouponIssueScheduler.kt            ← NEW
│   │   └── service/
│   │       ├── CouponIssueService.kt              ← NO CHANGE (그대로 호출됨)
│   │       └── WaitingQueueService.kt             ← NEW
│   └── global/
│       ├── config/
│       │   ├── RedisConfig.kt                     ← MODIFY (enterQueueScript Bean)
│       │   └── SchedulingConfig.kt                ← NEW (@EnableScheduling + WaitingQueueProperties)
│       └── exception/
│           └── ErrorCode.kt                       ← MODIFY (USER_ALREADY_IN_QUEUE)
└── resources/
    ├── application.yaml                            ← MODIFY (waiting-queue.* 추가)
    └── scripts/
        ├── issue_coupon.lua                        ← NO CHANGE
        └── enter_queue.lua                         ← NEW
```

---

## 7. Implementation Order

| Step | File | Description | Depends On |
|------|------|-------------|------------|
| 1 | `enter_queue.lua` | 발급/중복 체크 + ZADD + EXPIRE Lua | — |
| 2 | `RedisConfig.kt` | `enterQueueScript: RedisScript<Long>` Bean | Step 1 |
| 3 | `WaitingQueueRepository.kt` (+ `EnterResult`) | tryEnter / rank / size / popMin / recordResult / findResult | Step 2 |
| 4 | `ErrorCode.kt` | `USER_ALREADY_IN_QUEUE` 추가 | — |
| 5 | `QueueStatus.kt`, `QueueEnterResponse.kt`, `QueueStatusResponse.kt` | 응답 DTO + payload 파서 companion | — |
| 6 | `EventRepository.kt` | `findAllOpenAt(now)` JPQL 추가 | — |
| 7 | `WaitingQueueProperties.kt` + `SchedulingConfig.kt` | `@EnableScheduling` + `@ConfigurationProperties` 등록 | — |
| 8 | `WaitingQueueService.kt` | enter / status — Repository + EventRepository 조합 | Step 3, 4, 5, 6 |
| 9 | `WaitingQueueController.kt` | `/enter`, `/queue/status` 매핑 | Step 8 |
| 10 | `CouponIssueScheduler.kt` | `@Scheduled` drain — popMin → issue → recordResult + SOLD_OUT short-circuit | Step 3, 6, 7 + 기존 `CouponIssueService` |
| 11 | `application.yaml` | `waiting-queue.*` 키 추가 | Step 7 |

---

## 8. API Specification

### 8.1 POST /api/v1/events/{eventId}/enter

대기열 진입.

**Path / Query**:
- `eventId: UUID` (path)
- `userId: UUID` (query)

**Response 200**:
```json
{
  "status": "WAITING",
  "rank": 1234,
  "totalWaiting": 5000
}
```

**Errors**:
| HTTP | ErrorCode | 의미 |
|------|-----------|------|
| 404 | EVENT_NOT_FOUND (E404) | 이벤트 미존재 |
| 409 | EVENT_NOT_OPEN (E409-1) | 이벤트 시간 외 (period 검증 실패) |
| 409 | COUPON_ALREADY_ISSUED (CI409-1) | 이미 발급된 유저 (재진입 차단) |
| 409 | USER_ALREADY_IN_QUEUE (Q409-1) | 이미 대기열에 있음 (중복 enter 차단) |

### 8.2 GET /api/v1/events/{eventId}/queue/status

대기열 상태 / 발급 결과 조회.

**Path / Query**:
- `eventId: UUID` (path)
- `userId: UUID` (query)

**Response 200** (status에 따라 필드 가변):

| status | 추가 필드 | 의미 |
|--------|----------|------|
| `WAITING` | `rank`, `totalWaiting` | 큐에 있음 |
| `ISSUED` | `issueId` | 발급 성공 (couponIssueId) |
| `SOLD_OUT` | — | 매진 (해당 batch에서 stock 소진) |
| `ALREADY_ISSUED` | — | 이전 batch에서 이미 발급된 유저 |
| `FAILED` | `reason` | 기타 실패 (errorCode 전달) |
| `NOT_IN_QUEUE` | — | 큐에도 없고 결과 키도 없음 (TTL 만료 또는 미진입) |

**Errors**:
- 404 EVENT_NOT_FOUND (event 검증 실패) — *현재 설계는 status에서는 event 존재만 확인하지 않고 곧장 큐/결과 키를 본다. 단순화를 위해 event 검증 생략 가능 — § 11.2*

### 8.3 POST /api/v1/events/{eventId}/issue (NO CHANGE)

기존 redis-stock + kafka-consumer 직접 호출 경로. 본 feature는 **이를 변경하지 않는다** — 학습 비교용으로 유지.

운영 시 권장 사용 패턴:
- 정상 트래픽: `/issue` 직접 호출 (queue 우회)
- 피크 트래픽 / 플래시 세일: `/enter` + `/queue/status` 폴링 패턴

---

## 9. Testing Strategy

### 9.1 Test Scope

| 계층 | 대상 | 도구 | 검증 포인트 |
|-----|------|------|-----------|
| **L2 Repository** | `WaitingQueueRepository` | Kotest + MockK on `StringRedisTemplate` | Lua EVAL 인자, key 네이밍, ZRANK +1 변환 |
| **L2 Service** | `WaitingQueueService` | Kotest + MockK | EnterResult → Exception 매핑, status 분기 (result→ZRANK→NOT_IN_QUEUE) |
| **L2 Scheduler** | `CouponIssueScheduler` | Kotest + MockK | popMin → issue 호출 순서, SOLD_OUT short-circuit, BusinessException별 payload |
| **L3 Controller** | `WaitingQueueController` | `@WebMvcTest` + `springmockk` | enter/status JSON 직렬화, 에러 코드 매핑 |
| **L4 Integration** | 전체 큐 흐름 | `@SpringBootTest` + Testcontainers (Redis, Kafka, Postgres) | 동시 enter → tick 대기 → 결과 폴링 → 발급 건수 검증 |

### 9.2 핵심 테스트 케이스

**WaitingQueueRepository (L2)**:
- [ ] `tryEnter` Lua EVAL 호출 시 KEYS = `[waiting:{eId}, coupon:issued:{eId}]`, ARGV = `[userId, scoreMs, ttlSeconds]`
- [ ] `tryEnter` 반환 1 → SUCCESS, 0 → ALREADY_IN_QUEUE, -1 → ALREADY_ISSUED, 그 외 → IllegalStateException
- [ ] `rank`는 ZRANK 결과 + 1 (1-based)
- [ ] `popMin` 결과를 `UUID.fromString` 변환

**WaitingQueueService (L2)**:
- [ ] enter — Event 미존재 → EVENT_NOT_FOUND
- [ ] enter — period.contains=false → EVENT_NOT_OPEN
- [ ] enter — Lua 결과 매핑 (Result → 적절한 BusinessException)
- [ ] enter — SUCCESS 시 rank/totalWaiting 채워짐
- [ ] status — result key 우선 분기 (ISSUED:, SOLD_OUT, ALREADY_ISSUED, FAILED:)
- [ ] status — result 없고 ZRANK null → NOT_IN_QUEUE
- [ ] status — result 없고 ZRANK 있음 → WAITING + rank/total

**CouponIssueScheduler (L2)**:
- [ ] 진행 이벤트가 0개면 popMin 호출 안 됨
- [ ] popMin 결과가 비어있으면 issue 호출 안 됨
- [ ] 정상 issue 시 `recordResult(..., "ISSUED:${id}")`
- [ ] EVENT_SOLD_OUT 발생 시 잔여 유저들도 `SOLD_OUT` 기록 + 다음 issue 호출 안 됨
- [ ] COUPON_ALREADY_ISSUED 시 `ALREADY_ISSUED` 기록 후 다음 유저 처리 계속
- [ ] 알 수 없는 예외 시 `FAILED:UNKNOWN` 기록 + 다음 유저 처리 계속

**WaitingQueueController (L3)**:
- [ ] POST `/enter` 정상 — 200 + JSON 필드
- [ ] POST `/enter` 4xx — ErrorResponse 구조 (engine/ERROR_WRITE_GUIDE 준수)
- [ ] GET `/queue/status` 모든 status 분기 직렬화

**Integration (L4)**:
- [ ] 100개 동시 `/enter` → ZCARD = 100 (Lua atomicity)
- [ ] 동일 userId 2회 `/enter` → 첫 번째 200, 두 번째 409 USER_ALREADY_IN_QUEUE
- [ ] Scheduler 1 tick 후 `result:` 키 100개 생성, ZCARD = 0
- [ ] Stock=10 + Enter=100 → 결과 ISSUED 10개 + SOLD_OUT 90개
- [ ] **Kafka Consumer까지 연동**: ISSUED 10개에 대해 DB `coupon_issue` 테이블에 INSERT 10건 (CountDownLatch로 Consumer 완료 대기)

### 9.3 동시성 회귀 (concurrency-test 영향)

기존 `/issue` 경로 테스트는 **변경 없음** — 본 feature는 `/issue`를 건드리지 않는다. 신규 큐 경로용 테스트는 별도 클래스로 추가.

---

## 10. Success Criteria

- [ ] `POST /enter` 동시 호출 시 ZCARD가 호출 수와 일치 (race 없음)
- [ ] 동일 userId 2회 진입 시 두 번째는 409 USER_ALREADY_IN_QUEUE
- [ ] 이미 발급된 유저(`coupon:issued:{eventId}` SADD됨)의 `/enter` 시 409 COUPON_ALREADY_ISSUED
- [ ] Scheduler가 tick마다 batch_size만큼 popMin → issue 호출
- [ ] Scheduler 처리 후 `/queue/status` 응답이 WAITING → ISSUED/SOLD_OUT/ALREADY_ISSUED로 전이
- [ ] EVENT_SOLD_OUT 발생 시 batch 잔여 유저들에게 즉시 SOLD_OUT 결과 기록 (Lua 추가 호출 없이)
- [ ] Kafka Producer 실패 시 `result:` 키에 `FAILED:COUPON_PUBLISH_FAILED` + Redis 보상 (기존 redis-stock 동작 유지 확인)
- [ ] Spring graceful shutdown 시 진행 중 batch 완료 후 종료 (TaskScheduler `setWaitForTasksToCompleteOnShutdown`)
- [ ] 1-based rank 표기 (UI 친화)
- [ ] `application.yaml`의 `waiting-queue.batch-size`/`poll-interval-ms` 변경 시 즉시 반영 (재배포 시점)

---

## 11. Open Questions / Trade-offs

### 11.1 Scheduler crash 시 데이터 손실

**문제**: ZPOPMIN은 destructive — popped 후 처리 중 앱 크래시 시 해당 유저는 큐에서도 result 키에서도 사라진다.

**현 설계의 입장**: 학습 프로젝트 범위 밖. 다음 패턴 중 하나가 운영용:

- **a) Processing Set 패턴**: ZPOPMIN 대신 ZRANGEBYSCORE → MULTI: ZREM + SADD `processing:{eventId}` → 처리 후 SREM. 크래시 후 부팅 시 `processing:` Set의 잔여 유저 재처리
- **b) Redis Streams**: `XADD` + `XREADGROUP` + `XACK` — Kafka Consumer Group 패턴. PEL(Pending Entries List)이 자동으로 미-ack 메시지 재배달
- **c) Outbox**: enter 시 DB outbox 테이블에도 기록 → Scheduler가 DB 기준으로 처리. 가장 안전하지만 DB 부하가 다시 상승 (큐의 의의 약화)

학습 후속 phase: Redis Streams로 마이그레이션이 자연스러운 학습 경로.

### 11.2 status 엔드포인트의 event 검증 생략

`/queue/status`는 현재 설계에서 event 존재 검증을 하지 않는다 — `result:` 키와 `waiting:` 키 직접 조회만. 잘못된 eventId면 NOT_IN_QUEUE를 반환.

**의도**: latency 최소화. 폴링 빈도가 높은 엔드포인트라 DB hit을 피한다.

**Trade-off**: 잘못된 eventId에 대해 404 대신 NOT_IN_QUEUE라는 모호한 응답. 클라이언트는 enter에서 이미 검증되었다고 가정.

### 11.3 멀티 인스턴스 운영

여러 Spring 인스턴스가 동시에 `@Scheduled`를 돌리면 batch_size × 인스턴스_수 만큼 입장률이 곱해진다. 의도된 동작일 수도 있고 (수평 확장 = throughput 증가), 의도하지 않을 수도 있다 (전역 admission rate 유지 필요).

후자라면:
- ShedLock로 한 인스턴스만 tick에 active
- 또는 batch_size를 인스턴스 수로 나눠 설정

학습 프로젝트는 단일 인스턴스 가정. 분산 락은 별도 phase로 분리.

### 11.4 Event 캐싱

`Scheduler.findAllOpenAt`은 매 tick마다 DB 조회. tick=1s, OPEN 이벤트=수개면 DB 부하 무시 가능. 대규모 운영(수백 OPEN 이벤트 동시)이라면:

- Caffeine local cache (5초 TTL) — 가장 단순
- Redis cache (`active-events` Set 유지)

후속 phase 후보.

### 11.5 결과 통지 push (WebSocket/SSE)

Plan에서 OOS로 명시. 폴링은 `result:` 키 GET 1회씩이라 비싸지 않음 (Redis 100k ops/sec). 향후 SSE/WebSocket 전환 시:

- Kafka Consumer가 INSERT 후 SSE broadcast
- 또는 Scheduler가 결과 기록 시 Redis Pub/Sub

학습 가치는 충분하나 본 feature 범위 밖.

---

## 12. Convention References

본 설계는 [`CLAUDE.md`](../../../CLAUDE.md)와 `docs/engine/`의 가이드를 따른다:

| 가이드 | 적용 위치 |
|--------|----------|
| [`JPA_WRITE_GUIDE.md`](../../engine/JPA_WRITE_GUIDE.md) | `EventRepository.findAllOpenAt` JPQL — `@Embedded DateRange` 접근 |
| [`DTO_WRITE_GUIDE.md`](../../engine/DTO_WRITE_GUIDE.md) | Queue 응답 DTO — `@Schema` 명세, companion factory `fromResultPayload` |
| [`ERROR_WRITE_GUIDE.md`](../../engine/ERROR_WRITE_GUIDE.md) | `USER_ALREADY_IN_QUEUE` (`Q409-1`) 명명, `BusinessException` 사용, 4xx=warn 로깅 |
| [`TEST_WRITE_GUIDE.md`](../../engine/TEST_WRITE_GUIDE.md) | L2~L4 4-Layer 적용, Testcontainers Redis/Kafka |

---

## 13. Next Steps

1. [ ] 구현 시작 (`/pdca do 05-waiting-queue`)
2. [ ] 통합 테스트 — 동시 enter + tick 대기 + 결과 검증 (Kafka Consumer까지 latch)
3. [ ] Gap 분석 (`/pdca analyze 05-waiting-queue`)
4. [ ] Flash Sale 5/5 — 전체 시스템 통합 테스트 시나리오 작성

---

## Version History

| Version | Date | Changes | Author |
|---------|------|---------|--------|
| 0.1 | 2026-04-25 | Initial design — Sorted Set 큐, `@Scheduled` drain, result 키 패턴, SOLD_OUT short-circuit, 멀티 인스턴스/crash 트레이드오프 명시 | beomjin |
