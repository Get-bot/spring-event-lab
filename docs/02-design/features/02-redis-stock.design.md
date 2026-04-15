# Redis Stock Management Design Document

> **Summary**: Redis Lua 스크립트 기반 선착순 재고 차감 + 중복 방지 + DB 보상 전략의 상세 기술 명세
>
> **Project**: spring-event-lab
> **Version**: 0.0.1-SNAPSHOT
> **Author**: beomjin
> **Date**: 2026-04-15
> **Status**: Draft
> **Planning Doc**: [02-redis-stock.plan.md](../../01-plan/features/02-redis-stock.plan.md)
> **Depends On**: 01-event-crud (Event, CouponIssue 엔티티 + ErrorCode 체계)

---

## Executive Summary

| Perspective | Content |
|-------------|---------|
| **Problem** | DB 행 락으로 재고를 차감하면 동시 요청 시 데드락·커넥션 풀 고갈·초과 발급이 발생하고, 개별 Redis 명령 순차 호출은 명령 사이 race condition으로 중복/초과 발급 위험이 있다 |
| **Solution** | Redis Lua 스크립트로 SISMEMBER+GET/DECR+SADD를 단일 원자적 블록으로 실행하고, DB 저장 실패 시 SREM+INCR 보상 처리로 정합성을 보장한다 |
| **Function/UX Effect** | 유저가 발급 요청 시 1 RTT(Lua)로 100ms 이내 성공/매진/중복 응답을 즉시 받으며, 동일 유저 재요청은 즉시 거부된다 |
| **Core Value** | "개별 명령은 원자적이지만 조합은 아니다"를 Lua 스크립트로 해결하는 Redis 핵심 패턴을 학습하고, Spring `RedisScript<Long>` 연동까지 완성한다 |

---

## 1. Overview

### 1.1 Design Goals

- **Lua 스크립트 원자성**: 중복체크+차감+기록을 하나의 원자적 블록으로 묶어 race condition 제거
- **Lazy Init 패턴**: 재고 키를 첫 발급 요청 시 `SET NX EX`로 초기화하여 별도 스케줄러 불필요
- **보상 전략**: Redis 성공 → DB 실패 시 SREM+INCR로 일관성 복구
- **기존 코드베이스 활용**: `Event.period.contains()` 시간 검증, `CouponIssue` 엔티티 재사용, ErrorCode 패턴 준수
- **DDD Aggregate 경계**: `CouponIssueService`는 `EventRepository`를 직접 의존하되, Event Aggregate의 상태 변경은 하지 않음 (재고는 Redis가 관리)

### 1.2 Design Principles

- **Redis는 재고 게이트키퍼**: Redis가 수량 제어의 단일 진실 공급원(Source of Truth), DB는 영구 저장소
- **Fail-fast**: 시간 검증 → Lua(중복+재고) → DB 저장 순서로 가장 빠른 실패 경로를 먼저 실행
- **관심사 분리**: `CouponIssueService`(발급 유스케이스)와 `EventService`(CRUD) 분리
- **Spring 관용구**: `StringRedisTemplate`, `RedisScript<Long>`, `@Bean` 기반 Lua 스크립트 등록

---

## 2. Architecture

### 2.1 Component Diagram

```
┌──────────────┐     ┌─────────────────────────────────────────────────────────┐     ┌──────────────┐
│              │     │                   Spring Boot App                       │     │              │
│   Client     │────▶│  CouponIssueController                                 │     │  PostgreSQL  │
│ (Swagger UI) │     │      ↓                                                 │     │  (eventlab)  │
│              │     │  CouponIssueService                                    │     │              │
│              │     │      ↓              ↓              ↓                   │     │              │
│              │     │  EventRepository  CouponIssue    RedisStockRepository  │     │              │
│              │     │   (read-only)     Repository      ↓                   │     │              │
│              │     │      ↓              ↓           StringRedisTemplate    │────▶│  Redis 8     │
│              │     │  Event entity    CouponIssue      ↓                   │     │  (Lua EVAL)  │
│              │     │   .period          entity      RedisScript<Long>       │     │              │
│              │     │   .contains()                  (issue_coupon.lua)      │     │              │
│              │     │                                                        │     │              │
│              │◀────│   GlobalExceptionHandler (ErrorCode → ErrorResponse)   │     │              │
│              │     └─────────────────────────────────────────────────────────┘     └──────────────┘
└──────────────┘
```

### 2.2 Data Flow — 쿠폰 발급

```
POST /api/v1/events/{eventId}/issue?userId={userId}

1. [Controller] CouponIssueController.issue(eventId, userId)
   → CouponIssueService.issue(eventId, userId)

2. [Service - 시간 검증] eventRepository.findByIdOrNull(eventId)
   → event.period.contains(Instant.now())
   └─ false → throw BusinessException(EVENT_NOT_OPEN)

3. [Service - 재고 초기화] redisStockRepository.initStockIfAbsent(eventId, totalQuantity, ttl)
   → SET coupon:stock:{eventId} {totalQuantity} NX EX {ttlSeconds}
   → EXPIRE coupon:issued:{eventId} {ttlSeconds}  (issued Set TTL도 동기화)

4. [Service - Lua 스크립트] redisStockRepository.tryIssueCoupon(eventId, userId)
   → EVALSHA issue_coupon.lua [stockKey, issuedKey] [userId]
   └─ return -1 → throw BusinessException(COUPON_ALREADY_ISSUED)
   └─ return  0 → throw BusinessException(EVENT_SOLD_OUT)
   └─ return  1 → 발급 성공

5. [Service - DB 저장] couponIssueRepository.save(CouponIssue(eventId, userId))
   └─ 실패 시 → redisStockRepository.compensate(eventId, userId)
                 → SREM coupon:issued:{eventId} {userId}
                 → INCR coupon:stock:{eventId}
                 → throw (예외 전파)

6. [Controller] → 201 Created + CouponIssueResponse
```

### 2.3 Dependencies

| 신규 컴포넌트 | 의존하는 기존 컴포넌트 | 용도 |
|---------------|----------------------|------|
| `CouponIssueController` | `CouponIssueService` | REST 엔드포인트 |
| `CouponIssueService` | `EventRepository` (read-only) | Event 조회 + 시간 검증 |
| `CouponIssueService` | `CouponIssueRepository` (NEW) | DB 저장 |
| `CouponIssueService` | `RedisStockRepository` (NEW) | Redis 재고 연산 |
| `RedisStockRepository` | `StringRedisTemplate` (Spring auto-config) | Redis 명령 실행 |
| `RedisStockRepository` | `RedisScript<Long>` (NEW Bean) | Lua 스크립트 실행 |
| `RedisConfig` (NEW) | `spring-boot-starter-data-redis` | RedisScript Bean 등록 |
| `CouponIssue` entity | 기존 — 변경 없음 | DB 영구 저장 |
| `Event` entity | 기존 — 변경 없음 | `period.contains()` 활용 |
| `ErrorCode` enum | 기존 — 3개 값 추가 | 발급 관련 에러 코드 |

---

## 3. Detailed Design

### 3.1 Lua 스크립트 — `issue_coupon.lua`

> 위치: `src/main/resources/scripts/issue_coupon.lua`

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

**설계 결정 — Check-then-Act (읽기 우선, 쓰기 최소화)**:

Flash sale에서 재고 소진 후의 매진 요청이 전체 트래픽의 99%+를 차지한다.
DECR-first(Act-then-Check) 방식은 매진 경로에서도 쓰기 4회(SADD+DECR+INCR+SREM)가 발생하지만,
GET 선검사(Check-then-Act) 방식은 매진 경로를 **읽기 1회, 쓰기 0회**로 처리한다.

| 경로 | 호출 | 쓰기 |
|------|:----:|:----:|
| 매진 (99%+) | GET = 1회 | 0 |
| 중복 | GET + SISMEMBER = 2회 | 0 |
| 성공 (~0.1%) | GET + SISMEMBER + DECR + SADD = 4회 | 2 |

쓰기가 비싼 이유: AOF 영속화, 레플리카 전파, 메모리 할당/해제, Lua 블로킹 시간 증가.
성공 경로(0.1%)의 호출 수보다 매진 경로(99%+)의 쓰기 제거가 압도적으로 더 중요하다.

- `GET` → 검사 → `DECR` 패턴: Lua 내부이므로 원자적, 음수 진입 방지 → INCR 롤백 불필요
- 반환 타입 `Long`: Spring `RedisScript<Long>`과 호환
- KEYS 2개: Redis Cluster 환경 대비 (같은 해시 슬롯 보장을 위해 `{eventId}` 해시태그 사용 가능)

### 3.2 Redis Key 설계

| Key Pattern | Type | Purpose | TTL | 생성 시점 |
|-------------|------|---------|-----|----------|
| `coupon:stock:{eventId}` | String | 잔여 수량 | `endedAt - now + 1h` | Lazy Init (`SET NX EX`) |
| `coupon:issued:{eventId}` | Set | 발급된 userId 집합 | `endedAt - now + 1h` | Lazy Init 시 `EXPIRE` 설정 |

**TTL 계산**: `Duration.between(Instant.now(), event.period.endedAt).plusHours(1).toSeconds()`

### 3.3 RedisStockRepository

```kotlin
package com.beomjin.springeventlab.coupon.repository

@Repository
class RedisStockRepository(
    private val redisTemplate: StringRedisTemplate,
    private val issueCouponScript: RedisScript<Long>,
) {
    /**
     * 재고를 Redis에 초기화한다 (이미 존재하면 무시).
     * stock 키: SET NX EX, issued 키: EXPIRE만 설정.
     */
    fun initStockIfAbsent(eventId: UUID, totalQuantity: Int, ttlSeconds: Long) {
        val stockKey = stockKey(eventId)
        val issuedKey = issuedKey(eventId)

        val wasSet = redisTemplate.opsForValue()
            .setIfAbsent(stockKey, totalQuantity.toString(), Duration.ofSeconds(ttlSeconds))

        if (wasSet == true) {
            redisTemplate.expire(issuedKey, Duration.ofSeconds(ttlSeconds))
        }
    }

    /**
     * Lua 스크립트로 중복체크+차감+기록을 원자적으로 실행한다.
     * @return 1(성공), 0(매진), -1(이미 발급)
     */
    fun tryIssueCoupon(eventId: UUID, userId: UUID): Long {
        return redisTemplate.execute(
            issueCouponScript,
            listOf(stockKey(eventId), issuedKey(eventId)),
            userId.toString(),
        ) ?: throw IllegalStateException("Lua script returned null")
    }

    /**
     * DB 저장 실패 시 Redis 보상: 발급 기록 제거 + 재고 복원.
     */
    fun compensate(eventId: UUID, userId: UUID) {
        redisTemplate.opsForSet().remove(issuedKey(eventId), userId.toString())
        redisTemplate.opsForValue().increment(stockKey(eventId))
    }

    private fun stockKey(eventId: UUID): String = "coupon:stock:$eventId"
    private fun issuedKey(eventId: UUID): String = "coupon:issued:$eventId"
}
```

### 3.4 RedisConfig

```kotlin
package com.beomjin.springeventlab.global.config

@Configuration
class RedisConfig {

    @Bean
    fun issueCouponScript(): RedisScript<Long> {
        return RedisScript.of(ClassPathResource("scripts/issue_coupon.lua"), Long::class.java)
    }
}
```

**설계 결정**: `RedisScript`를 `@Bean`으로 등록하면 Spring이 스크립트 SHA를 캐싱하여 `EVALSHA`로 실행한다. 매 호출마다 스크립트 본문을 전송하지 않아 네트워크 효율적.

### 3.5 CouponIssueRepository

```kotlin
package com.beomjin.springeventlab.coupon.repository

@Repository
interface CouponIssueRepository : JpaRepository<CouponIssue, UUID>
```

### 3.6 CouponIssueService

```kotlin
package com.beomjin.springeventlab.coupon.service

@Service
class CouponIssueService(
    private val eventRepository: EventRepository,
    private val couponIssueRepository: CouponIssueRepository,
    private val redisStockRepository: RedisStockRepository,
) {
    private val log = KotlinLogging.logger {}

    /**
     * 선착순 쿠폰 발급.
     * 1) Event 조회 + 시간 검증
     * 2) Redis Lazy Init
     * 3) Lua 스크립트 (중복+차감+기록)
     * 4) DB 저장 (실패 시 Redis 보상)
     */
    @Transactional
    fun issue(eventId: UUID, userId: UUID): CouponIssueResponse {
        // 1) Event 조회 + 시간 검증
        val event = eventRepository.findByIdOrNull(eventId)
            ?: throw BusinessException(ErrorCode.EVENT_NOT_FOUND)

        if (!event.period.contains(Instant.now())) {
            throw BusinessException(ErrorCode.EVENT_NOT_OPEN)
        }

        // 2) Redis 재고 Lazy Init
        val ttlSeconds = Duration.between(Instant.now(), event.period.endedAt)
            .plusHours(1).toSeconds()
        redisStockRepository.initStockIfAbsent(eventId, event.totalQuantity, ttlSeconds)

        // 3) Lua 스크립트 실행
        val result = redisStockRepository.tryIssueCoupon(eventId, userId)
        when (result) {
            -1L -> throw BusinessException(ErrorCode.COUPON_ALREADY_ISSUED)
             0L -> throw BusinessException(ErrorCode.EVENT_SOLD_OUT)
        }

        // 4) DB 저장 + 실패 보상
        val couponIssue = try {
            couponIssueRepository.save(CouponIssue(eventId, userId))
        } catch (e: Exception) {
            log.error(e) { "DB 저장 실패, Redis 보상 실행: eventId=$eventId, userId=$userId" }
            redisStockRepository.compensate(eventId, userId)
            throw e
        }

        return CouponIssueResponse.from(couponIssue)
    }
}
```

**설계 결정**:
- `@Transactional`은 DB 저장 부분에만 실질적 영향. Redis 연산은 트랜잭션 바깥에서 독립 실행됨
- `event.period.contains(Instant.now())`: 기존 `DateRange` Value Object 재사용
- DB의 `uk_coupon_issue(event_id, user_id)` Unique 제약이 **최종 방어선** — Lua SISMEMBER를 통과한 극히 드문 edge case도 DB에서 차단

### 3.7 CouponIssueController

```kotlin
package com.beomjin.springeventlab.coupon.controller

@RestController
@RequestMapping("/api/v1/events")
@Tag(name = "Coupon Issue", description = "쿠폰 발급 API")
class CouponIssueController(
    private val couponIssueService: CouponIssueService,
) {
    @PostMapping("/{eventId}/issue")
    @Operation(
        summary = "쿠폰 발급",
        description = "선착순 쿠폰을 발급합니다. Redis에서 재고를 차감하고 DB에 기록합니다.",
    )
    fun issue(
        @PathVariable eventId: UUID,
        @RequestParam userId: UUID,
    ): ResponseEntity<CouponIssueResponse> {
        val response = couponIssueService.issue(eventId, userId)
        return ResponseEntity.status(HttpStatus.CREATED).body(response)
    }
}
```

> **Note**: `userId`는 인증 미구현 상태의 임시 설계. 향후 `SecurityContext`에서 추출하도록 변경 예정.

### 3.8 DTO

#### CouponIssueResponse

```kotlin
package com.beomjin.springeventlab.coupon.dto.response

data class CouponIssueResponse(
    val id: UUID,
    val eventId: UUID,
    val userId: UUID,
    val createdAt: Instant?,
) {
    companion object {
        fun from(entity: CouponIssue): CouponIssueResponse = CouponIssueResponse(
            id = entity.id,
            eventId = entity.eventId,
            userId = entity.userId,
            createdAt = entity.createdAt,
        )
    }
}
```

### 3.9 ErrorCode 추가

기존 `ErrorCode` enum에 3개 값을 추가:

```kotlin
// Coupon Issue Errors
COUPON_ALREADY_ISSUED(HttpStatus.CONFLICT, "CI409-1", "이미 발급된 쿠폰입니다."),
EVENT_SOLD_OUT(HttpStatus.GONE, "E410", "이벤트 재고가 소진되었습니다."),
REDIS_UNAVAILABLE(HttpStatus.SERVICE_UNAVAILABLE, "R503", "Redis 서비스에 일시적으로 접근할 수 없습니다."),
```

**설계 결정**:
- `EVENT_OUT_OF_STOCK`(기존, 409)은 DB 레벨의 재고 부족. `EVENT_SOLD_OUT`(410 Gone)은 Redis 레벨의 매진으로 **의미적 구분**
- `COUPON_ALREADY_ISSUED`는 `CI` prefix로 CouponIssue 도메인임을 명시
- `REDIS_UNAVAILABLE`은 향후 CircuitBreaker Fallback용으로 예비 등록

---

## 4. Redis Key Lifecycle

```
[이벤트 생성]                         [첫 발급 요청]                    [이벤트 종료 + 1h]
     │                                     │                                  │
     ▼                                     ▼                                  ▼
  DB에 Event 저장               SET coupon:stock:{id} NX EX         Redis에서 키 자동 삭제
  (Redis에는 아직 없음)          EXPIRE coupon:issued:{id}           (TTL 만료)
                                     │
                                     ▼
                               [발급 요청마다]
                               EVALSHA issue_coupon.lua
                                 → SISMEMBER + GET/DECR + SADD
```

---

## 5. Error Handling Matrix

| 실패 지점 | 상태 | 처리 |
|-----------|------|------|
| Event 미존재 | Redis 변경 없음 | `EVENT_NOT_FOUND` (404) |
| 이벤트 시간 외 | Redis 변경 없음 | `EVENT_NOT_OPEN` (409) |
| Redis `initStockIfAbsent` 실패 | Redis 변경 없음 | 예외 전파 (500) |
| Lua 반환 -1 (중복) | Redis 변경 없음 | `COUPON_ALREADY_ISSUED` (409) |
| Lua 반환 0 (매진) | Redis 변경 없음 | `EVENT_SOLD_OUT` (410) |
| Lua 반환 1 → DB INSERT 성공 | Redis ✅ DB ✅ | `201 Created` |
| Lua 반환 1 → DB INSERT 실패 | Redis ✅ DB ❌ | **보상**: SREM+INCR → 예외 전파 |
| 보상(SREM+INCR) 실패 | Redis ✅ DB ❌ | 로그 기록 + 예외 전파 (수동 조치 필요) |

---

## 6. File Structure (신규/변경)

```
src/main/
├── kotlin/com/beomjin/springeventlab/
│   ├── coupon/
│   │   ├── controller/
│   │   │   └── CouponIssueController.kt      ← NEW
│   │   ├── dto/
│   │   │   └── response/
│   │   │       └── CouponIssueResponse.kt     ← NEW
│   │   ├── repository/
│   │   │   ├── CouponIssueRepository.kt       ← NEW
│   │   │   └── RedisStockRepository.kt        ← NEW
│   │   └── service/
│   │       └── CouponIssueService.kt          ← NEW
│   └── global/
│       ├── config/
│       │   └── RedisConfig.kt                 ← NEW
│       └── exception/
│           └── ErrorCode.kt                   ← MODIFY (+3 enum values)
└── resources/
    └── scripts/
        └── issue_coupon.lua                   ← NEW
```

---

## 7. Implementation Order

구현 순서는 의존성 방향(하위 → 상위)을 따른다:

| Step | File | Description | Depends On |
|------|------|-------------|------------|
| 1 | `issue_coupon.lua` | Lua 스크립트 작성 | — |
| 2 | `RedisConfig.kt` | `RedisScript<Long>` Bean 등록 | Step 1 |
| 3 | `ErrorCode.kt` | 3개 enum 값 추가 | — |
| 4 | `CouponIssueResponse.kt` | 응답 DTO | — |
| 5 | `CouponIssueRepository.kt` | JPA Repository 인터페이스 | — |
| 6 | `RedisStockRepository.kt` | Redis 재고 연산 (init, tryIssue, compensate) | Step 1, 2 |
| 7 | `CouponIssueService.kt` | 발급 유스케이스 orchestration | Step 3, 5, 6 |
| 8 | `CouponIssueController.kt` | REST 엔드포인트 | Step 4, 7 |

---

## 8. API Specification

### POST /api/v1/events/{eventId}/issue

| Item | Detail |
|------|--------|
| Method | `POST` |
| Path | `/api/v1/events/{eventId}/issue` |
| Path Param | `eventId` — UUID, 이벤트 ID |
| Query Param | `userId` — UUID, 사용자 ID (임시, 향후 SecurityContext 전환) |
| Request Body | 없음 |
| Success | `201 Created` + `CouponIssueResponse` |
| Error 404 | `EVENT_NOT_FOUND` — 이벤트 미존재 |
| Error 409-1 | `EVENT_NOT_OPEN` — 이벤트 미오픈/종료 |
| Error 409-2 | `COUPON_ALREADY_ISSUED` — 중복 발급 |
| Error 410 | `EVENT_SOLD_OUT` — 매진 |

#### Response Body (201)

```json
{
  "id": "019680a1-...",
  "eventId": "019680a0-...",
  "userId": "019680a0-...",
  "createdAt": "2026-04-15T10:30:00Z"
}
```

---

## 9. Success Criteria

- [ ] Lua 스크립트가 SISMEMBER+GET/DECR+SADD를 원자적으로 실행한다
- [ ] 수량이 0이 되면 이후 모든 요청은 `410 EVENT_SOLD_OUT` 응답을 받는다
- [ ] 동일 유저 재요청은 `409 COUPON_ALREADY_ISSUED`로 거부된다
- [ ] Redis와 DB의 발급 건수가 일치한다
- [ ] DB 저장 실패 시 Redis 보상(SREM+INCR)이 동작하고 로그가 남는다
- [ ] Redis 재고 키는 이벤트 종료 + 1h 후 자동 삭제된다
- [ ] 발급 API p99 < 100ms (Redis Lua 1 RTT)

---

## 10. Next Steps

1. [ ] 구현 (`/pdca do redis-stock`)
2. [ ] Gap 분석 (`/pdca analyze redis-stock`)
3. [ ] 다음 feature: `03-concurrency-test` — 이 로직이 동시성 환경에서 안전한지 부하 테스트로 증명

---

## Version History

| Version | Date | Changes | Author |
|---------|------|---------|--------|
| 0.1 | 2026-04-15 | Initial design — Lua 스크립트, RedisStockRepository, 보상 전략 | beomjin |
