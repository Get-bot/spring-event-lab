# Coupon Test Backfill Planning Document

> **Summary**: coupon 도메인(redis-stock + kafka-consumer)의 L1~L3 테스트 공백을 메우고, 기존 L4 `CouponIssueConcurrencyTest`를 Test Pyramid 원칙에 맞게 재정비하여 **피드백 속도 ↑ + 실패 원인 명확화** 달성
>
> **Project**: spring-event-lab
> **Version**: 0.0.1-SNAPSHOT
> **Author**: beomjin
> **Date**: 2026-04-20
> **Status**: Draft
> **Roadmap**: Test Quality Backfill (Flash Sale 로드맵과 병행 — 05-waiting-queue 이전/이후 언제든 착수 가능)
> **Depends On**: 01-event-crud (통과), 02-redis-stock (완료), 04-kafka-consumer (완료)
> **Knowledge Base**: [TEST_WRITE_GUIDE.md](../../engine/TEST_WRITE_GUIDE.md)

---

## Executive Summary

| Perspective | Content |
|-------------|---------|
| **Problem** | coupon 도메인은 L4 `CouponIssueConcurrencyTest` **1개가 전부**. Event 도메인이 L1~L4 모두 갖춘 것과 대비되어 Test Pyramid가 역전됨. 테스트 실행에 Testcontainers 기동 비용이 매번 발생하고, 실패 시 Redis/Kafka/DB 중 어느 층의 문제인지 원인 특정이 어려움 |
| **Solution** | TEST_WRITE_GUIDE의 4계층 원칙대로 **L1 도메인 순수 단위 → L2 Service/Producer/Consumer MockK → L3 @WebMvcTest/@DataJpaTest/Redis slice**를 backfill. 동시에 L4 Concurrency Test는 "오직 L4에서만 의미 있는 시나리오(동시 경합)"만 남겨 **L4 의존도 축소** |
| **Function/UX Effect** | 전체 테스트 실행 시간이 "Testcontainers 기동 포함 ~30초"에서 "L1+L2 수 초 + L3 수 초 + L4 10초"로 **계층별 분리 실행** 가능. CI/로컬에서 "빠른 피드백(L1+L2)" → "변경 연관 층 집중 실행" → "최종 E2E" 흐름 확보 |
| **Core Value** | coupon 도메인의 기능은 이미 완성(kafka-consumer Match Rate 97%). 이 backfill은 **"작동하는 코드"에서 "작동을 증명하는 safety net"으로** 격상. CLAUDE.md의 DDD 관점(Aggregate 경계, Entity 캡슐화, DTO 팩토리)과 04-kafka-consumer Lessons(Jackson 3, `JacksonJsonSerializer`)를 **단위 테스트로 영속화**하여 향후 `05-waiting-queue` 구현 시 회귀 방어선 역할 |

---

## 1. Overview

### 1.1 Purpose

flash-sale 로드맵의 1~4단계(`01-event-crud`, `02-redis-stock`, `03-concurrency-test`, `04-kafka-consumer`)를 거치며 **기능은 완성되었으나 coupon 도메인의 테스트 피라미드는 뒤집힌 채 남음**. 본 feature는 신규 기능 추가 없이 **테스트 품질 복구(backfill)만 수행**한다.

### 1.2 학습 포인트

- **Test Pyramid 역전 현상의 실제 사례와 복구**: L4 하나로 모든 것을 커버하려 할 때의 리스크(느림, 원인 추적 어려움, CI 비용)
- **MockK의 Kafka 검증 패턴**: `KafkaTemplate.send(...).get()` 체인을 `SettableListenableFuture` 또는 `CompletableFuture`로 mock하는 방법
- **Spring Kafka의 `@SpringBootTest` 없이 Consumer 로직 검증**: `@RetryableTopic`이 붙은 메서드를 그냥 호출하는 L2 단위 + `EmbeddedKafka`로 통합 검증하는 L3/L4 분리
- **`@DataJpaTest`로 UK 제약 검증**: Entity의 `uk_coupon_issue(event_id, user_id)` 제약이 실제 DB에서 동작함을 슬라이스 단위로 확인
- **Redis slice 테스트**: Testcontainers Redis를 `@SpringBootTest` 대신 `@TestConfiguration`에 얹어 Repository slice처럼 운용하는 기법

---

## 2. Scope

### 2.1 In Scope

#### L1 — Domain Unit Tests (신규)
- [ ] `CouponIssueTest` — Entity 컨벤션(CLAUDE.md) + DDD Aggregate 경계 준수 검증
  - id 기본값 생성: `UuidCreator.getTimeOrderedEpoch()` 호출 후 `id` 필드가 non-null UUID
  - **UUID v7 시간 순서성 보존**: `sleep(2ms)` 간격으로 100개 생성 → `sortedBy { it.id }` 결과가 생성 순서와 일치
  - id 주입 경로: `CouponIssue(id = fixedId, eventId, userId)` → `entity.id == fixedId` (Consumer 재시도 멱등성의 기반)
  - **Aggregate 경계 준수**: reflection으로 `CouponIssue::class` 필드 검사 → `Event` 타입 필드가 존재하지 않고, `eventId: UUID`만 존재함을 assertion (`@ManyToOne` 금지 원칙)
  - 캡슐화: `id`/`eventId`/`userId` 세터가 `protected` — 같은 패키지 외부에서 대입 시도 시 컴파일 에러. 테스트에선 주 생성자로만 설정 가능함을 문서/주석으로 명시
- [ ] `CouponIssueMessageTest` — data class + Jackson 3 직렬화 검증
  - `data class` equality/copy — 필드 동일 시 equals = true, 하나라도 다르면 false
  - Instant 필드 보존: `copy(issuedAt = other)` 후 나머지 필드 유지
  - **Jackson 3 roundtrip**: `tools.jackson.databind.ObjectMapper` + `JacksonModules.kotlinModule()` 등록 → `writeValueAsString` → `readValue<CouponIssueMessage>()` 결과가 원본과 동일 (04-kafka-consumer Lessons L1 반영, `JacksonJsonSerializer`/`JacksonJsonDeserializer`의 기반이 Jackson 3임을 단위 수준에서 증명)
  - `Instant`의 ISO-8601 포맷 검증 (밀리초 이상 정밀도 유지)
- [ ] `CouponIssueResponseTest` — DTO 팩토리 컨벤션(CLAUDE.md) 검증
  - `CouponIssueResponse.from(entity)` — entity의 id/eventId/userId/createdAt이 Response에 그대로 매핑
  - `entity.createdAt == null` (JPA persist 전) 시 Response의 `createdAt`도 null (현재 구조상)
  - direct 생성자 `CouponIssueResponse(id, eventId, userId, createdAt)` — Producer가 Consumer DB save 전 즉시 응답 시 사용 (createdAt = `Instant.now()`)
- [ ] `IssueResultTest` — `fromCode(-1|0|1)` 매핑, 알 수 없는 코드 `IllegalStateException` 검증

#### L2 — Service/Producer/Consumer Unit Tests (신규)
- [ ] `CouponIssueServiceTest` — `EventService`와 동일한 MockK 패턴. 시나리오:
  - EVENT_NOT_FOUND (404)
  - EVENT_NOT_OPEN — 시작 전/종료 후
  - `initStockIfAbsent` 호출 파라미터 검증 (TTL 계산)
  - Redis Lua 반환 `ALREADY_ISSUED` / `SOLD_OUT` / `SUCCESS` 세 경로
  - SUCCESS 시 Producer에 넘기는 `CouponIssueMessage.id == response.id` 보장
  - Producer가 예외 던지면 Service 통과 (Service 자신이 추가 보상 안 함)
- [ ] `CouponIssueProducerTest` — `KafkaTemplate` mock + `RedisStockRepository` mock
  - send() 성공 → 반환값 검증, compensate 호출 없음
  - send() 타임아웃 → `redisStockRepository.compensate(eventId, userId)` 1회 호출 + `BusinessException(COUPON_PUBLISH_FAILED)` 예외
  - partition key가 `eventId.toString()` 인지 `slot<ProducerRecord>` 또는 인자 캡처로 검증
- [ ] `CouponIssueConsumerTest` — `CouponIssueRepository` mock
  - save 성공 경로 (정상 종료)
  - `DataIntegrityViolationException` 발생 → 예외 전파 없음(ack 됨), 경고 로그
  - 기타 `DataAccessException` → 예외 전파 (@RetryableTopic이 받도록)
  - `handleDlt(...)` 호출 → ERROR 로그 검증
- [ ] `RedisStockRepositoryTest` (L2 — `StringRedisTemplate` mock)
  - `initStockIfAbsent` → `setIfAbsent(key, value, Duration.ofSeconds(ttl))` 정확한 인자
  - `tryIssueCoupon` → `execute(script, keys, args)` → 반환 코드 → `IssueResult` 매핑
  - `tryIssueCoupon` null 반환 → `IllegalStateException`
  - `compensate` → SREM + INCR 순서 검증
  - `restoreStock` → INCR만

#### L3 — Slice Tests (신규)
- [ ] `CouponIssueControllerTest` — `@WebMvcTest(CouponIssueController::class)` + `@MockkBean(CouponIssueService)`
  - POST `/api/v1/events/{eventId}/issue?userId={userId}` → 201 Created + Response JSON 필드 검증
  - `EVENT_NOT_FOUND` → 404 + errorCode "E404"
  - `EVENT_NOT_OPEN` → 409 + errorCode "E409-1"
  - `COUPON_ALREADY_ISSUED` → 409 + errorCode "CI409-1"
  - `EVENT_SOLD_OUT` → 410 + errorCode "E410"
  - `COUPON_PUBLISH_FAILED` → 503 + errorCode "CI503"
  - `userId` 파라미터 누락 → 400
  - `eventId` UUID 형식 오류 → 400
- [ ] `CouponIssueRepositoryTest` — `@DataJpaTest` + Testcontainers Postgres (`IntegrationTestBase` 재사용)
  - save → id 반환, `createdAt` 자동 세팅 (`BaseCreatedTimeEntity`)
  - 동일 `(eventId, userId)` 두 번째 save → `DataIntegrityViolationException` (UK)
  - id 주입 save — Consumer 경로 시뮬레이션
- [ ] `RedisStockRepositoryIntegrationTest` — Testcontainers Redis slice (최소 `@SpringBootTest(classes=[RedisConfig])` + `@ServiceConnection`)
  - 실제 Lua 스크립트 실행 3경로 (SUCCESS/ALREADY/SOLD_OUT)
  - compensate 후 stock 복원 + issued Set 제거 확인
  - TTL 적용 확인 (setIfAbsent 후 `TTL` 명령)

#### L4 — Concurrency Test 재정비
- [ ] 기존 `CouponIssueConcurrencyTest` 4개 TC 전수 점검:
  - **TC-01 (초과 발급 0건)** — **유지**. L4에서만 의미 있는 시나리오 (실제 경합 검증)
  - **TC-02 (동일 userId 중복)** — **제거**. L3 Redis slice (`RedisStockRepositoryIntegrationTest`)에서 Lua 스크립트가 ALREADY_ISSUED 반환하는지만 확인하면 충분
  - **TC-03 (매진 상태 반복 요청)** — **제거 또는 축소**. Lua 반환 SOLD_OUT 경로가 L3 Redis slice에서 검증되므로 L4에서 굳이 1,000건 반복할 가치 낮음. 삭제 or 수량 10건으로 축소
  - **TC-04 (Redis-DB 정합성)** — **유지**. Kafka Consumer 비동기 소비까지 포함한 E2E는 L4 고유
- [ ] `awaitDbCount` 폴링 헬퍼 — 유지하되 `IntegrationTestBase`로 이동 검토 (재사용성)
- [ ] 최종 L4는 TC 2개로 축소 → 실행 시간 ~17초 → ~8초 예상

#### Fixture 정비
- [ ] `CouponIssueFixture` (NEW) — `EventFixture`와 동일 패턴. 메서드 후보:
  - `couponIssue(eventId, userId)`, `couponIssueWithId(id, eventId, userId)`
  - `message(eventId, userId)`, `response(id, eventId, userId)`
- [ ] 기존 `EventFixture.openEvent()`는 그대로 활용

### 2.2 Out of Scope

- 성능 벤치마크 (JMH, Gatling) — 별도 feature
- 실제 Kafka broker 부하 테스트 — Testcontainers Kafka 로는 부족
- Contract Test (Pact 등) — Consumer/Producer가 같은 프로젝트라 불필요
- Mutation Testing (PIT)
- JaCoCo/Kover 커버리지 목표치 설정 — 결과는 보게 하되, 의무화하지 않음

---

## 3. Requirements

### 3.1 Functional Requirements

| ID | Requirement | Priority | Status |
|----|-------------|----------|--------|
| FR-01 | L1 도메인 단위 테스트 4개 작성 (`CouponIssue`, `CouponIssueMessage` + Jackson3 roundtrip, `CouponIssueResponse`, `IssueResult`) | High | Pending |
| FR-02 | L2 Service/Producer/Consumer/Repository 단위 테스트 4개 작성 | High | Pending |
| FR-03 | L3 Controller @WebMvcTest 1개 작성 (모든 에러 매핑 포함) | High | Pending |
| FR-04 | L3 @DataJpaTest + Redis slice 2개 작성 | Medium | Pending |
| FR-05 | L4 `CouponIssueConcurrencyTest` 재정비 (TC 2개로 축소) | High | Pending |
| FR-06 | `CouponIssueFixture` 신규 작성 | Medium | Pending |

### 3.2 Non-Functional Requirements

| Category | Criteria | Measurement Method |
|----------|----------|-------------------|
| Feedback Speed | L1+L2 전체 실행 시간 < 2초 | `./gradlew test --tests "*Test" -x CouponIssueConcurrencyTest*` 측정 |
| Layer Isolation | L1/L2는 Testcontainers 없이 실행 가능 | 테스트 클래스에 `@SpringBootTest` / `@ServiceConnection` 존재 여부 |
| Deterministic | 100회 반복 실행 시 0 flakiness | `./gradlew test --rerun-tasks` 수 차례 |
| Convention | TEST_WRITE_GUIDE 4계층 분류 준수 | 리뷰 체크리스트 |

---

## 4. Gap Map — 현재 vs 목표

### 4.1 coupon 도메인 테스트 커버리지 Before

```
┌─────────────────────────────────────────────────────┐
│  L4 ████████████                CouponIssueConcurrency│
│  L3 ░░░░░░░░░░░░░░░░░░░░░░░░░░  (0개)                │
│  L2 ░░░░░░░░░░░░░░░░░░░░░░░░░░  (0개)                │
│  L1 ░░░░░░░░░░░░░░░░░░░░░░░░░░  (0개)                │
└─────────────────────────────────────────────────────┘
```

### 4.2 Target After

```
┌─────────────────────────────────────────────────────┐
│  L4 ███                         Concurrency (2 TC)   │
│  L3 ████████                    Controller + JPA + Redis│
│  L2 ██████████                  Service/Producer/Consumer/Repo│
│  L1 ██████████████              Entity/Message/Enum  │
└─────────────────────────────────────────────────────┘
```

### 4.3 Expected File Tree After

```
src/test/kotlin/com/beomjin/springeventlab/
├── coupon/
│   ├── CouponIssueConcurrencyTest.kt        ← MODIFY (TC-02, TC-03 제거, TC-04 정비)
│   ├── EventCrudIntegrationTest.kt          ← UNCHANGED
│   ├── consumer/
│   │   └── CouponIssueConsumerTest.kt       ← NEW (L2)
│   ├── controller/
│   │   ├── EventControllerTest.kt           ← UNCHANGED
│   │   └── CouponIssueControllerTest.kt     ← NEW (L3)
│   ├── dto/
│   │   ├── message/
│   │   │   └── CouponIssueMessageTest.kt    ← NEW (L1 + Jackson3 roundtrip)
│   │   ├── request/ ...                      ← UNCHANGED
│   │   └── response/
│   │       ├── CouponIssueResponseTest.kt   ← NEW (L1, from(entity) 팩토리)
│   │       └── EventResponseTest.kt         ← UNCHANGED
│   ├── entity/
│   │   ├── CouponIssueTest.kt               ← NEW (L1)
│   │   └── ...                               ← UNCHANGED
│   ├── producer/
│   │   └── CouponIssueProducerTest.kt       ← NEW (L2)
│   ├── repository/
│   │   ├── CouponIssueRepositoryTest.kt             ← NEW (L3 @DataJpaTest)
│   │   ├── IssueResultTest.kt                       ← NEW (L1)
│   │   ├── RedisStockRepositoryTest.kt              ← NEW (L2 mock)
│   │   ├── RedisStockRepositoryIntegrationTest.kt   ← NEW (L3 Redis slice)
│   │   └── ...                               ← UNCHANGED
│   └── service/
│       ├── CouponIssueServiceTest.kt        ← NEW (L2)
│       └── EventServiceTest.kt              ← UNCHANGED
└── support/
    ├── CouponIssueFixture.kt                ← NEW
    ├── EventFixture.kt                      ← UNCHANGED
    └── IntegrationTestBase.kt               ← MODIFY? (awaitDbCount 이동 검토)
```

---

## 5. Shift-Down Plan — L4에서 L1~L3로 이전

기존 `CouponIssueConcurrencyTest` 4개 TC 각각이 **어느 계층에서 가장 적절히 검증되는지** 매핑:

| 기존 TC | Before (위치) | After (위치) | 이유 |
|---------|--------------|-------------|------|
| TC-01 초과 발급 0건 (3,000→1,000) | L4 | **L4 유지** | 실제 스레드 경합만이 '초과 발급' 버그를 발현시킴. Mock으론 재현 불가 |
| TC-02 동일 userId 중복 | L4 | **L3 Redis slice** | Lua 스크립트의 SISMEMBER 단계가 ALREADY_ISSUED 반환하는지는 실제 Redis 한 대로 충분 |
| TC-03 매진 반복 요청 | L4 | **L3 Redis slice** + L2 Service Unit | Lua SOLD_OUT 반환은 L3, Service가 이 코드를 받아 EVENT_SOLD_OUT 던지는 것은 L2 |
| TC-04 Redis-DB 정합성 | L4 | **L4 유지** + L2로 부분 이전 | Consumer 비동기 소비까지 포함한 정합성은 L4, Producer가 publish했다는 사실은 L2 |

**결과**: L4는 TC-01 + TC-04 **2개**만 남음. 실행 시간 ~50% 감소 예상.

---

## 6. Implementation Order

| Step | Layer | File | Dependency | Priority |
|------|-------|------|------------|----------|
| 1 | Fixture | `CouponIssueFixture.kt` | — | High |
| 2 | L1 | `CouponIssueTest.kt` (UUID v7 시간순서성 + Aggregate 경계 reflection) | Fixture | High |
| 3 | L1 | `IssueResultTest.kt` | — | High |
| 4 | L1 | `CouponIssueMessageTest.kt` (Jackson 3 roundtrip 포함) | Fixture | Medium |
| 5 | L1 | `CouponIssueResponseTest.kt` (`from(entity)` 팩토리) | Fixture | Medium |
| 6 | L2 | `RedisStockRepositoryTest.kt` | — | High |
| 7 | L2 | `CouponIssueServiceTest.kt` | Fixture | High |
| 8 | L2 | `CouponIssueProducerTest.kt` | Fixture | High |
| 9 | L2 | `CouponIssueConsumerTest.kt` | Fixture | High |
| 10 | L3 | `CouponIssueControllerTest.kt` | Fixture | High |
| 11 | L3 | `CouponIssueRepositoryTest.kt` | IntegrationTestBase | Medium |
| 12 | L3 | `RedisStockRepositoryIntegrationTest.kt` | IntegrationTestBase | Medium |
| 13 | L4 | `CouponIssueConcurrencyTest.kt` (재정비) | 모든 앞선 테스트 | High |

**Rationale**: Fixture → L1(독립) → L2(MockK만) → L3(Testcontainers) → L4 재정비 순서로 쌓아 올림. 각 Step 완료 시점마다 빌드 초록이 유지됨.

---

## 7. Testing Strategy Reference

각 계층 작성 시 참조 문서/기존 예제:

| 계층 | Reference | 핵심 패턴 |
|------|-----------|----------|
| L1 | `EventTest.kt`, `DateRangeTest.kt` | `DescribeSpec` + 순수 객체 생성 + 불변식 검증 |
| L2 | `EventServiceTest.kt` | `FunSpec` + `mockk<T>()` + `clearAllMocks()` + `every { } returns` + `verify { }` |
| L3 Controller | `EventControllerTest.kt` | `@WebMvcTest` + `@MockkBean` + MockMvc |
| L3 Repository | `EventQueryRepositoryTest.kt` | `@DataJpaTest` + `IntegrationTestBase` + Testcontainers |
| L4 | `CouponIssueConcurrencyTest.kt` (현재) | `@SpringBootTest` + companion containers + CountDownLatch |

---

## 8. Success Criteria

- [ ] L1 테스트 4개 작성, 모두 Spring 없이 밀리초 단위 실행 (UUID v7 시간순서성, Aggregate 경계 reflection, Jackson 3 roundtrip, `from(entity)` 팩토리 모두 포함)
- [ ] L2 테스트 4개 작성, MockK only, Testcontainers 없이 초 단위 실행
- [ ] L3 테스트 3개 작성, 에러 매핑/UK 제약/Lua 3경로 커버
- [ ] L4 Concurrency 2개 TC로 축소 (TC-02, TC-03 제거)
- [ ] `CouponIssueFixture` 작성, Service/Controller 테스트에서 재사용
- [ ] `./gradlew test` 전체 그린 (0 flakiness, 3회 반복 실행 검증)
- [ ] `./gradlew test --tests "*Test" -x "*ConcurrencyTest*" -x "*Integration*"` 실행 시간 < 5초 (L1+L2+WebMvc slice)
- [ ] TEST_WRITE_GUIDE의 4계층 원칙 100% 준수 (리뷰 체크)

---

## 9. Risks & Mitigations

| Risk | Likelihood | Mitigation |
|------|-----------|-----------|
| Kafka `@RetryableTopic` 메서드 L2 단위 테스트 시 어노테이션이 효과 없어 재시도 경로 검증 불가 | High | L2에선 "메서드 본문 로직"만 검증. 실제 재시도는 L4에서만. 이 차이를 Design/Report에 명시 |
| `KafkaTemplate.send().get()` mock이 복잡 (`CompletableFuture`) | Medium | `mockk` + `every { kafkaTemplate.send(...) } returns ...`로 `SendResult` 또는 미래 객체 반환. 샘플 코드 작성 단계에서 확정 |
| L3 Redis slice 띄우는 방법이 Spring Boot 4에서 달라졌을 수 있음 | Low | `@DataRedisTest` 또는 `@SpringBootTest(classes = [RedisConfig, RedisStockRepository])` 선택. TEST_WRITE_GUIDE 확장 검토 |
| TC-02/03 제거 시 혹시 놓치는 버그? | Low | 제거된 시나리오가 L3 Redis slice + L2 Service Unit 조합으로 동등 커버됨을 Design 단계에서 매핑 테이블로 증명 |
| 기존 `@MockkBean` 패턴과 Spring Boot 4 호환성 | Low | springmockk 5.0.1은 Spring Boot 4.x 지원 확인 (`testImplementation("com.ninja-squad:springmockk:5.0.1")` 이미 등록) |
| **Spring Boot 4 `@DataJpaTest` + Flyway**: CLAUDE.md 알려진 주의사항에 "spring-boot-starter-flyway 필수"가 있고 메인 앱은 `ddl-auto: validate` 사용 중. 슬라이스 테스트에서 Flyway가 비활성화되면 스키마가 없어서 `validate` 실패 위험 | Medium | Design 단계에서 `@DataJpaTest(properties = ["spring.flyway.enabled=true"])` 또는 `@ImportAutoConfiguration(FlywayAutoConfiguration::class)` 명시 결정. `IntegrationTestBase`(기존 `EventQueryRepositoryTest`가 사용 중) 패턴 검증 후 그대로 계승 |
| **Jackson 3 ObjectMapper 등록**: `tools.jackson.module:jackson-module-kotlin`이 의존성에 있지만, 단위 테스트에서 Spring이 autoconfig한 ObjectMapper를 재현하려면 `KotlinModule` + `JavaTimeModule` 수동 등록 필요 | Low | `CouponIssueMessageTest`에 `ObjectMapper.Builder()` 유틸을 Fixture에 두고 재사용. Spring 컨텍스트 로딩 없이 Jackson 3 설정만 필요 |

---

## 10. Next Steps

1. [ ] Design 문서 작성 (`/pdca design coupon-test-backfill`) — 각 테스트 파일의 구체 시나리오/assertion 명세, Kafka mock 패턴 결정
2. [ ] 구현 (`/pdca do coupon-test-backfill`) — Step 1~12 순차 진행, 각 Step마다 그린 확인
3. [ ] Gap 분석 (`/pdca analyze coupon-test-backfill`) — Design Match Rate 산출
4. [ ] 완료 보고서 (`/pdca report coupon-test-backfill`)
5. [ ] 이후 `05-waiting-queue` 구현 시 본 테스트 세트가 회귀 safety net 역할

---

## 11. Out-of-Band Notes

- **설치할 신규 의존성 없음** — 기존 Kotest/MockK/springmockk/testcontainers 전부 활용
- **TEST_WRITE_GUIDE 업데이트 필요 여부**: 본 feature 완료 후 "Kafka mock 패턴" 섹션 추가 검토 (Lessons Learned에 포함)
- **Design v0.2 업데이트 (04-kafka-consumer)**: 본 feature와 독립적으로 진행 가능. 테스트 작성 중 Design 문서 오류(L1~L5 Lessons Learned) 참고 가능

---

## Version History

| Version | Date | Changes | Author |
|---------|------|---------|--------|
| 0.1 | 2026-04-20 | Initial Plan — L1~L4 backfill + ConcurrencyTest 재정비 + Shift-Down 매핑 | beomjin |
| 0.2 | 2026-04-20 | CLAUDE.md 가이드 보강 — (1) DDD Aggregate 경계 reflection 검증 (2) UUID v7 시간순서성 단위 테스트 (3) `CouponIssueResponse.from(entity)` 팩토리 L1 테스트 신설 (4) Jackson 3 roundtrip 검증 (5) Flyway @DataJpaTest Risk 추가 | beomjin |
