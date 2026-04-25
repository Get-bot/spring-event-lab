# Waiting Queue Gap Analysis

> **Match Rate**: 99.0%
> **Date**: 2026-04-25
> **Status**: PASS (≥ 90%)
> **Feature**: 05-waiting-queue
> **Design Doc**: [05-waiting-queue.design.md](../02-design/features/05-waiting-queue.design.md)
> **Plan Doc**: [05-waiting-queue.plan.md](../01-plan/features/05-waiting-queue.plan.md)
> **Analyzed By**: bkit:gap-detector

---

## Executive Summary

| Perspective | Content |
|-------------|---------|
| **Verification Result** | Design 문서의 모든 의무 항목(§2.5 의존성, §3 상세 설계, §4 시퀀스, §5 에러 매트릭스, §6 파일 구조, §7 구현 순서, §8 API)이 구현에 1:1로 반영됨 |
| **Match Rate** | **99.0%** — P0/P1 gap 없음. P2 3건 모두 design 문서 측 표기 결함이거나 구현 측의 의도된 개선(`EnterResult.fromCode` Map 캐싱) |
| **Convention Compliance** | 6/6 카테고리 통과 — `BusinessException` 사용, `@Schema` 부착, `findByIdOrNull`, DDD aggregate 분리, hash tag, 패키지 구조 |
| **Recommendation** | Report phase 진입 가능 (`/pdca report 05-waiting-queue`). 후속 작업으로 Design §9 Testing Strategy(L2~L4)의 통합 테스트 작성 권장 |

---

## 1. Coverage Summary

| Category | Total | Implemented | Match Rate |
|----------|-------|-------------|-----------|
| Design Items (§3 Detailed Design) | 11 | 11 | 100.0% |
| Files (§6 File Structure) | 15 (11 NEW + 4 MODIFY) | 15 | 100.0% |
| Implementation Order Steps (§7) | 11 | 11 | 100.0% |
| API Contract (§8) | 2 endpoints | 2 endpoints | 100.0% |
| Error Handling Matrix (§5) | 12 cases | 12 cases | 100.0% |
| Sequence Diagrams (§4) | 2 flows | 2 flows | 100.0% |
| Convention Compliance | 6 | 6 | 100.0% |
| Dependencies Table (§2.5) | 11 components | 11 (1 minor naming gap) | 95.0% |
| **Overall (가중)** | — | — | **99.0%** |

---

## 2. Item-by-Item Verification

| # | Design Item | Status | Notes |
|---|-------------|--------|-------|
| 1 | §2.5 `WaitingQueueController` (NEW) | ✅ | `coupon/controller/WaitingQueueController.kt` |
| 2 | §2.5 `WaitingQueueService` (NEW) | ✅ | `coupon/service/WaitingQueueService.kt` |
| 3 | §2.5 `WaitingQueueRepository` (NEW) | ✅ | `coupon/repository/WaitingQueueRepository.kt` |
| 4 | §2.5 `CouponIssueScheduler` (NEW) | ✅ | `coupon/scheduler/CouponIssueScheduler.kt` |
| 5 | §2.5 `enter_queue.lua` (NEW) | ✅ | `resources/scripts/enter_queue.lua` |
| 6 | §2.5 `RedisConfig` (MODIFY) — `enterQueueScript` Bean | ✅ | RedisConfig.kt:13-14 |
| 7 | §2.5 `SchedulingConfig` (NEW) | ✅ | `global/config/SchedulingConfig.kt` |
| 8 | §2.5 `EventRepository` (MODIFY) — `findAllOpenAt` | ⚠️ | design §2.5 표는 메서드명을 `findAllByEventStatusAndStartedAtBeforeAndEndedAtAfter`로 표기했으나, §3.8 본문 코드 예시와 실제 구현은 명시적 `@Query` + 메서드명 `findAllOpenAt`. 본문(§3.8)의 결정이 더 명확하므로 구현이 옳음. design 측 표기 결함 |
| 9 | §2.5 `ErrorCode` (MODIFY) — `USER_ALREADY_IN_QUEUE` | ✅ | ErrorCode.kt:37, `Q409-1` |
| 10 | §2.5 `CouponIssueService` (NO CHANGE) | ✅ | 변경 없음 확인 |
| 11 | §3.1 Redis Key Design — `waiting:{eventId}` hash tag | ✅ | `waitingKey()` = `"waiting:{$eventId}"` |
| 12 | §3.1 Redis Key Design — `result:{eventId}:{userId}` | ✅ | `resultKey()` = `"result:{$eventId}:$userId"` |
| 13 | §3.1 — `coupon:issued:{eventId}` 재사용 | ✅ | `issuedKey()` = `"coupon:issued:{$eventId}"` |
| 14 | §3.2 Lua KEYS/ARGV 시그니처 | ✅ | KEYS[1]=waiting, KEYS[2]=issued; ARGV[1..3]=userId/score/ttl |
| 15 | §3.2 Lua 반환 코드 (-1/0/1) 의미 | ✅ | -1 SISMEMBER hit, 0 ZSCORE hit, 1 ZADD 성공 |
| 16 | §3.2 Lua: SISMEMBER → ZSCORE → ZADD → EXPIRE NX (TTL<0) | ✅ | enter_queue.lua:9-22 — 순서/조건 정확 |
| 17 | §3.3 `RedisConfig.enterQueueScript: RedisScript<Long>` | ✅ | 정확 |
| 18 | §3.4 `tryEnter` 시그니처 | ✅ | `(UUID, UUID, Long, Long): EnterResult` |
| 19 | §3.4 `rank` 1-based +1 변환 | ✅ | `rank(...)?.let { it + 1 }` |
| 20 | §3.4 `size` ZCARD | ✅ | `opsForZSet().size(...) ?: 0L` |
| 21 | §3.4 `popMin(eventId, count)` UUID 변환 | ✅ | `mapNotNull { it.value?.let(UUID::fromString) }` |
| 22 | §3.4 `recordResult(eventId, userId, payload, ttl)` | ✅ | 정확 (ttl: Duration) |
| 23 | §3.4 `findResult` | ✅ | 정확 |
| 24 | §3.4 `EnterResult.fromCode` 매핑 | ✅ | -1/0/1 → ALREADY_ISSUED/ALREADY_IN_QUEUE/SUCCESS, 그 외 IllegalStateException. CODE_MAP 사용은 design 코드와 다른 구현이지만 동작 동일하고 더 효율적 (Map 캐싱) — gap 아님 |
| 25 | §3.5 enter 흐름: Event 조회 → period.contains → tryEnter → rank/size | ✅ | WaitingQueueService.kt:33-58 정확 |
| 26 | §3.5 EnterResult별 BusinessException 매핑 | ✅ | ALREADY_ISSUED→COUPON_ALREADY_ISSUED, ALREADY_IN_QUEUE→USER_ALREADY_IN_QUEUE, SUCCESS→Unit |
| 27 | §3.5 status 흐름: result → rank → NOT_IN_QUEUE | ✅ | WaitingQueueService.kt:71-86 정확 |
| 28 | §3.5 ttlSeconds = `Duration.between(now, endedAt).plusHours(1).toSeconds()` | ✅ | 정확 |
| 29 | §3.6 `@Scheduled(fixedDelayString = "...waiting-queue.poll-interval-ms:1000...")` | ✅ | 정확 |
| 30 | §3.6 drainQueues → drainOneEvent | ✅ | 구조 일치 |
| 31 | §3.6 SOLD_OUT short-circuit (drainRemainingAsSoldOut + return) | ✅ | EVENT_SOLD_OUT 발생 시 잔여 popped 유저에 SOLD_OUT 기록 후 return |
| 32 | §3.6 BusinessException별 payload 분기 | ✅ | EVENT_SOLD_OUT→"SOLD_OUT", COUPON_ALREADY_ISSUED→"ALREADY_ISSUED", else→"FAILED:${name}" |
| 33 | §3.6 알 수 없는 예외 → "FAILED:UNKNOWN" + log.error + 다음 유저 계속 | ✅ | `catch (Exception)` 분기 정확 |
| 34 | §3.7 Controller `POST /enter`, `GET /queue/status` | ✅ | RequestMapping `/api/v1/events`, `@PostMapping("/{eventId}/enter")`, `@GetMapping("/{eventId}/queue/status")` |
| 35 | §3.7 `@Tag` / `@Operation` 부착 | ✅ | name="Waiting Queue", description 부착 |
| 36 | §3.7 응답 DTO `@Schema` | ✅ | QueueEnterResponse, QueueStatusResponse, QueueStatus 모두 부착 |
| 37 | §3.7 `QueueStatusResponse.fromResultPayload` 파서 | ✅ | ISSUED/SOLD_OUT/ALREADY_ISSUED/FAILED + UNKNOWN_PAYLOAD fallback 일치 |
| 38 | §3.7 QueueStatus enum 6값 | ✅ | WAITING/ISSUED/SOLD_OUT/ALREADY_ISSUED/FAILED/NOT_IN_QUEUE |
| 39 | §3.8 `findAllOpenAt` JPQL (status=OPEN AND startedAt<=now AND endedAt>now) | ✅ | EventRepository.kt:16-23 정확 |
| 40 | §3.9 `@EnableScheduling` | ✅ | SchedulingConfig.kt:10 |
| 41 | §3.9 `taskScheduler` Bean (poolSize=2, prefix="scheduler-", waitForTasksToCompleteOnShutdown=true) | ✅ | 정확 (`awaitTerminationSeconds=10` 포함) |
| 42 | §3.9 `@EnableConfigurationProperties(WaitingQueueProperties::class)` | ✅ | SchedulingConfig.kt:11 |
| 43 | §3.10 `USER_ALREADY_IN_QUEUE(CONFLICT, "Q409-1", ...)` | ✅ | 정확 |
| 44 | §3.11 application.yaml `waiting-queue.poll-interval-ms / batch-size / result-ttl-seconds` | ✅ | application.yaml:65-68 |
| 45 | §3.11 `WaitingQueueProperties` `@ConfigurationProperties(prefix="waiting-queue")` | ✅ | 기본값 1000/100/3600 일치 |
| 46 | §4.1 정상 흐름 시퀀스 (enter→ZADD→ZRANK/ZCARD, tick→popMin→issue→recordResult) | ✅ | 코드 흐름 일치 |
| 47 | §4.2 SOLD_OUT short-circuit 시퀀스 | ✅ | drainRemainingAsSoldOut에서 Lua 호출 없이 SET만 호출 |
| 48 | §5 Error Matrix — 12 cases (404/409/200 분기, payload 형식) | ✅ | 모든 분기 구현 일치 (NOT_IN_QUEUE, FAILED:UNKNOWN 포함) |
| 49 | §6 File Structure 경로 일치 | ✅ | 15개 파일 모두 명시 경로에 존재 |
| 50 | §7 Implementation Order 11 steps 산출물 | ✅ | 모두 존재 |

---

## 3. Gap List

### P0 (Critical)
- 없음.

### P1 (High)
- 없음.

### P2 (Documentation / Cosmetic)

1. **§2.5 표 vs §3.8 본문 불일치** (design 측 문서 결함, 구현 측 gap 아님)
   - §2.5 Dependencies 표는 신규 메서드명을 `findAllByEventStatusAndStartedAtBeforeAndEndedAtAfter`로 표기
   - §3.8 본문 코드 예시와 실제 구현은 명시적 `@Query` + 메서드명 `findAllOpenAt`
   - **결론**: §3.8 본문 결정("메서드 이름 derivation은 가독성 떨어져 명시적 `@Query` 선택")이 더 명확. 구현이 옳음
   - **권장**: design 문서의 §2.5 표 수정

2. **§3.8 EventRepository 인터페이스 시그니처에 `EventQueryRepository` 누락**
   - design §3.8 코드는 `interface EventRepository : JpaRepository<Event, UUID>, EventQueryRepository`로 표기
   - 실제 `EventQueryRepository`는 별도 `@Repository class`로 구현되어 있고 `EventRepository`는 그것을 extend하지 않음
   - 본 feature 이전부터 그러했고, 본 feature는 인터페이스 시그니처를 변경하지 않음 → 실질적 영향 없음
   - **권장**: design 문서를 실제 구조에 맞춰 수정

3. **`EnterResult.fromCode` 구현 차이 (의도된 개선)**
   - design은 `entries.find { it.code == code }`로 매번 리니어 스캔
   - 구현은 `private val CODE_MAP = entries.associateBy { it.code }`로 사전 빌드 후 O(1) lookup
   - 의도된 성능 개선이며 동작·반환값·예외 모두 design 명세 충족
   - **결론**: gap이 아닌 개선이므로 보고만

---

## 4. Convention Compliance

| 항목 | 상태 | 근거 |
|------|------|------|
| `BusinessException(ErrorCode.X)` 사용 | ✅ | WaitingQueueService 3건, Scheduler 분기 모두 ErrorCode 기반 |
| `@field:` Bean Validation | N/A | enter/status는 `@PathVariable`/`@RequestParam`만 사용 — Bean Validation 어노테이션 부착 없음 (design §8 명세와 일치, query param에 Body validation 없음) |
| `@Schema` 응답 DTO 부착 | ✅ | QueueEnterResponse / QueueStatusResponse 모든 필드 + enum class에 부착 |
| `findByIdOrNull` 사용 (EventRepository) | ✅ | WaitingQueueService.kt:34 — `import org.springframework.data.repository.findByIdOrNull` |
| `protected set` Entity 변경 없음 | ✅ | 본 feature는 Entity 무수정 (Event/CouponIssue 미변경) |
| DDD Aggregate 분리 (`@ManyToOne` 없음) | ✅ | 신규 코드는 ID 참조만 사용 (UUID), `@ManyToOne` 도입 없음 |
| 패키지 구조 (`coupon/scheduler/` 신규 패키지) | ✅ | design §6 File Structure와 일치 |
| Logger 패턴 `KotlinLogging.logger {}` | ✅ | Scheduler.kt:16 — top-level `private val log` 패턴 (Kotlin 관용) |
| ErrorCode 명명 규칙 (`{DOMAIN}_{CONDITION}` + 서브코드 `Q409-1`) | ✅ | `USER_ALREADY_IN_QUEUE` + `Q409-1` |
| Hash tag 일관성 (`{eventId}`) | ✅ | waiting/result/issued 키 모두 `{eventId}` 패턴 — Redis Cluster 호환 |

---

## 5. Recommended Actions

### Immediate (P0/P1)
- 없음. 모든 P0/P1 gap 없음.

### Short-term (post-Check)
- **테스트 코드 작성**: design §9 Testing Strategy의 L2 Repository / L2 Service / L2 Scheduler / L3 Controller / L4 Integration 테스트는 본 분석 범위 외이지만 PDCA Do의 잔여 작업으로 즉시 착수 권장
- 특히 **L4 Integration**: 100개 동시 enter → 1 tick 대기 → 결과 폴링 → DB INSERT 건수 검증 (CountDownLatch + Testcontainers Redis/Kafka/PG)

### Documentation Update (P2)
- design 문서 §2.5 Dependencies 표의 `EventRepository` 행에서 메서드명을 `findAllOpenAt` (명시적 `@Query` 사용)로 수정
- design 문서 §3.8 코드 예시에서 `interface EventRepository : JpaRepository<Event, UUID>, EventQueryRepository`를 `interface EventRepository : JpaRepository<Event, UUID>`로 수정 (실제 구조와 일치)

### Follow-up (Open Questions §11에서 이미 식별됨, 본 feature gap 아님)
- §11.1 Scheduler crash 시 데이터 손실 → Redis Streams 마이그레이션 학습 phase
- §11.3 멀티 인스턴스 운영 시 ShedLock
- §11.4 Event 캐싱 (`@Cacheable` Caffeine)
- §11.5 SSE/WebSocket 결과 푸시

### Next Step
- **`/pdca report 05-waiting-queue`** (Match Rate 99.0% ≥ 90% — Report 단계로 진입 가능)

---

## 6. PDCA Status

```
Feature: 05-waiting-queue
Phase: Check (Gap Analysis) ✅
Match Rate: 99.0%
Iteration: 0/5 (불필요 — Match Rate ≥ 90%)
─────────────────────────────────
[Plan] ✅ → [Design] ✅ → [Do] ✅ → [Check] ✅ → [Report] ⏳
```

---

## Version History

| Version | Date | Match Rate | Iteration | Author |
|---------|------|-----------|-----------|--------|
| 0.1 | 2026-04-25 | 99.0% | 0 | bkit:gap-detector |
