# Changelog

All notable changes to this project will be documented in this file.

---

## [2026-04-20] - Kafka Consumer Feature Complete (Flash Sale Roadmap 4/5)

### Added
- **4개 신규 컴포넌트** — Peak Load Shifting 패턴 구현 (450 lines)
  - `CouponIssueMessage.kt` — Kafka 메시지 스키마 (id, eventId, userId, issuedAt)
  - `CouponIssueProducer.kt` — 발행 + 실패 시 Redis 보상 (SREM+INCR)
  - `CouponIssueConsumer.kt` — `@RetryableTopic` + `@DltHandler` + exclude UK violation
  - `KafkaConfig.kt` — ProducerFactory/ConsumerFactory/Template Bean + couponIssueTopic() 통합

### Methodology
- **UUID v7 사전 생성**: Service가 `UuidCreator.getTimeOrderedEpoch()` → Producer 메시지에 주입 → 즉시 응답 id 확정
- **동기 발행 + 비동기 처리**: `send().get(3s)` 대기 후 Redis 보상 또는 201 응답 → Consumer가 DB 소화 속도로 처리
- **선언적 DLT**: `@RetryableTopic(attempts=4, backoff exponential, dltTopicSuffix=".DLT")` → 수동 DefaultErrorHandler 제거
- **이중 멱등성**: exclude + try/catch 로그 후 삼킴 + DB UK 제약
- **Partition key = eventId**: 같은 이벤트 메시지는 같은 파티션으로 순서 보장

### Verified
- **Match Rate**: 97% (기능 gap 0, documentation debt 3% ← Spring Kafka 4.x API versioning)
- **Success Criteria**: 9/9 (UUID 사전 생성, 즉시 응답, Redis 보상, @RetryableTopic, Consumer 멱등성, DLT, 동시성 테스트 회귀 등)
- **Error Handling**: 11/11 경로 커버 (Event 미존재~DLT까지)
- **Concurrency Test**: 4개 TC (TC-01 초과 발급, TC-02 중복, TC-03 매진, TC-04 Redis-DB 정합성) 모두 통과 with `awaitDbCount()` Consumer 대기
- **Design Match**: 9/9 구현 순서, 9/9 설계 결정 일치
- **Execution Time**: ~10초 (Kafka + Consumer 처리)

### Documentation
- Completion Report: `docs/04-report/features/04-kafka-consumer.report.md` (v1.0)
- Gap Analysis: `docs/03-analysis/04-kafka-consumer.analysis.md` (v0.1, Match Rate 97%)
- Design Evolution: v0.1 (2026-04-20) — Spring Kafka 4.x API 코드 스니펫 추가 권고

### Learning
- **L1**: Design 작성 시 "최신 코드 실행 환경 버전"을 먼저 확인할 것 — v0.1은 Spring Kafka 2.x/3.x API로 작성, 실제는 4.0.4
  - G1-G2: `JsonSerializer` → `JacksonJsonSerializer` (Spring Kafka 4 표준)
  - G3: `backoff =` → `backOff =` + `org.springframework.kafka.annotation.BackOff` (spring-retry 분리)
  - G4: `buildProducerProperties(null)` → `buildProducerProperties()` (Spring Boot 4 API)
  - G8: retry topic naming (`topicSuffixingStrategy` 명시)
- **L2**: Documentation Debt ≠ Functional Gap — 97% 달성의 의미는 "기능 100% + API 버전 차이 3%"
- **L3**: Consumer 멱등성은 3층 방어 필수 (Kafka exclude + Consumer try/catch + DB UK 제약)
- **L4**: `awaitDbCount()` 폴링으로 프로덕션 코드 오염 방지 (테스트만 사용)
- **L5**: `@RetryableTopic` 선언적 DLT가 Spring Kafka 4.0.4 표준 — 수동 DefaultErrorHandler 제거 완료
- **Roadmap Progress**: Flash Sale 4/5 완성, 마지막 단계는 `05-waiting-queue` (Kafka 앞단 대기열)

---

## [2026-04-17] - Concurrency Test Suite Complete (Flash Sale Roadmap 3/5)

### Added
- **1개 신규 통합 테스트 클래스** — 고동시성 환경 검증 (210 lines)
  - `CouponIssueConcurrencyTest.kt` — Kotest FunSpec 기반, companion object containers
  - 4개 Test Case: TC-01(초과 발급), TC-02(중복 발급), TC-03(매진), TC-04(Redis-DB 정합성)

### Methodology
- **이중 래치(Double Latch) 패턴** — startLatch(1) 동시 출발 + doneLatch(taskCount) 완료 대기
- **poolSize 분리 전략** — 3,000 tasks를 200 threads로 처리 (batch 방식, OS ulimit 안전)
- **Helper 함수** — `createOpenEvent()`, `concurrentExecute()` 재사용성 극대화
- **3중 검증** — successCount + soldOutCount + DB count 일치 확인

### Verified
- **TC-01**: 1,000개 쿠폰, 3,000건 동시 요청 → 정확히 1,000건 발급 (초과 0건)
- **TC-02**: 동일 userId 100건 동시 → 1건만 발급 (중복 0건)
- **TC-03**: 매진 이벤트에 1,000건 요청 → 추가 발급 0건
- **TC-04**: Redis issued Set 크기 == DB 발급 건수 (정합성 완벽)
- Design Match Rate: 100% (v0.4 완전 일치, 4회 반복 갱신)
- Execution Time: ~17초 (4 TC 합계, 타임아웃 120초 내)

### Documentation
- Completion Report: `docs/04-report/features/concurrency-test.report.md` (v1.0)
- Design Evolution: v0.1 → v0.4 (4회 갱신: Plan 검증 → redis-stock 반영 → 구현 반영 → PR #5 리뷰 반영)

### Learning
- "동시성 버그는 코드 리뷰로 안 잡힌다, 테스트로만 잡힌다" 원칙 입증
- Testcontainers 기반 재현 가능한 테스트 환경 (로컬/CI 동일)
- redis-stock feature의 신뢰도 완성 및 flash sale roadmap 3/5 달성

---

## [2026-04-16] - Redis Stock Management Feature Complete (PR #4 Review Fixes)

### Added
- **9개 신규 파일** — Lua 스크립트, Redis 관련 컴포넌트, DB 트랜잭션 분리 서비스
  - `issue_coupon.lua` — SISMEMBER + GET/DECR + SADD + EXPIRE 원자적 스크립트
  - `RedisConfig.kt` — RedisScript<Long> Bean 등록
  - `RedisStockRepository.kt` — 재고 초기화, 발급 시도, 보상 처리
  - `CouponIssueService.kt` — 발급 유스케이스 orchestration (no @Transactional)
  - `CouponIssueTxService.kt` — DB 저장 + 예외 유형별 보상 (FIX-6)
  - `CouponIssueController.kt` — REST 엔드포인트
  - `CouponIssueRepository.kt`, `IssueResult.kt`, `CouponIssueResponse.kt`

### Changed
- `ErrorCode.kt` — 3개 enum 값 추가 (COUPON_ALREADY_ISSUED, EVENT_SOLD_OUT, REDIS_UNAVAILABLE)
- `ErrorCodeMapper.kt` — 410 GONE, 503 SERVICE_UNAVAILABLE 매핑 추가
- `GlobalExceptionHandler.kt` — RedisConnectionFailureException/RedisSystemException → 503 처리 (FIX-5)

### Fixed
- **FIX-3**: Lua script ARGV[2] TTL 설정 검증 — issued Set에 동적 TTL 적용
- **FIX-5**: GlobalExceptionHandler Redis 예외 핸들러 추가
- **FIX-6**: CouponIssueTxService 분리로 Redis 호출 중 DB 커넥션 미점유 (HikariCP 고갈 방지)
- **FIX-7**: Exception-aware compensation — DataIntegrityViolationException(UK) vs DataAccessException(기타) 분기
- **FIX-4**: Redis key hash tags `{$eventId}` for Cluster compatibility
- **ErrorCode refactoring**: HttpStatus 제거 → ErrorCodeMapper 확장 함수로 분리

### Verified
- Design Match Rate: 97% (78% → 97%, 2 iterations)
- Lua 스크립트 원자성 — SISMEMBER + GET/DECR + SADD + EXPIRE 하나의 블록으로 실행
- Redis 자료구조 타입 일관성 — stock(String), issued(Set) 명확히 구분
- HTTP 상태코드 구분 — 409 Conflict vs 410 Gone
- DB 커넥션 효율성 — Redis 호출 중 connection 미점유

### Documentation
- Completion Report: `docs/04-report/features/redis-stock.report.md` (v1.1)
- Known Issues section with verification requirements
- PR #4 fixes 반영 및 미해결 이슈 추적

### Learning
- Redis 자료구조 타입 충돌 WRONGTYPE 버그 사전 방지 방법
- Check-then-Act 패턴으로 매진 경로 성능 극대화 (매진=99%+ 트래픽)
- DB 트랜잭션 범위 축소로 고동시성 지원 가능
- PR 리뷰 기반 반복적 개선의 중요성 (2 iterations)

---

## [2026-04-15] - Redis Stock Management Feature Complete (Initial)

### Added
- **8개 신규 파일** — Redis 재고 관리 기본 구조
- Lua 스크립트 (3 RTT → 1 RTT 아키텍처)
- Redis Lazy Init 패턴 (SET NX EX)
- DB 보상 전략 (SREM + INCR)

### Fixed
- **GAP-01**: issued 키 WRONGTYPE 버그
- **GAP-02**: @Transactional 누락
- **GAP-03**: EVENT_SOLD_OUT HTTP 409→410
- **GAP-04**: COUPON_ALREADY_ISSUED prefix
- **GAP-05**: REDIS_UNAVAILABLE 미등록

### Verified
- Initial Design Match Rate: 78% → 97% (Iteration 1)
- Success Criteria: 7/7 pass

---

## [2026-04-14] - Event CRUD Test Suite Complete

### Added
- **81개 Kotest 6.1.0 기반 통합 테스트** (L1:52, L2:6, L3:18, L4:4)
  - L1 Domain: DateRangeTest (16개), EventStatusTest (15개), EventTest (21개)
  - L2 Service: EventServiceTest (6개)
  - L3 Slice: EventCreateRequestTest, EventResponseTest, EventQueryOrdersTest, EventQueryRepositoryTest, EventControllerTest (18개)
  - L4 Integration: EventCrudIntegrationTest (4개)
- **MockK 1.14.9 + springmockk 5.0.1** 전 계층 통일 (Kotlin-native mocking)
- **ProjectConfig** (io.kotest.provided) — Kotest 6.1.0 필수 설정
- **EventFixture** — 테스트 객체 생성 일관성
- **IntegrationTestBase** 리팩토링 (@Container + @JvmField)

### Changed
- Gradle: kotest-runner-junit5 6.1.0, kotest-extensions-spring 6.1.0, kotest-assertions-core 6.1.0 추가
- Gradle: mockk 1.14.9, springmockk 5.0.1 추가
- Package 이름: `spring_event_lab` → `springeventlab` (Spring Boot 4)
- L3 Slice 테스트: beforeSpec → beforeTest (트랜잭션 격리)
- L3 Controller Slice: @MockitoBean → @MockkBean (springmockk 5.0.1)

### Fixed
- EventQueryRepository 의존성: @DataJpaTest에서 명시적 @Import 필수
- Kotest 6.1.0: ProjectConfig 필수 위치 규칙 적용 (성능 최적화)
- Spring Boot 4 호환: Testcontainers 패키지 경로 업데이트

### Verified
- Design Match Rate: 95% (88% → 95%, EventTest 구현 후)
- FR Coverage: 100% (17/17 functional requirements)
- Test Execution Time: 6초 (병렬 최적화 가능)
- Compatibility: Kotlin 2.3.20, Spring Boot 4.0.5, PostgreSQL 18, Redis, Kafka

### Documentation
- Feature Completion Report: `docs/04-report/06-event-crud-test.report.md`
- Gap Analysis: `docs/03-analysis/06-event-crud-test.analysis.md`

---

## [2026-04-13] - Event CRUD Design (v0.3)

### Added
- Design document with test structure specification
- Kotest 6.1.0 Specs (DescribeSpec, FunSpec) 설계
- Mock strategy for L1-L4 layers
- Learning guide and Kotest DSL cheatsheet
- 상세 구현 명세 (10개 테스트 클래스, FR-T01~T17)

---

## [2026-04-10] - Event CRUD Test Planning

### Added
- Feature planning document: `docs/01-plan/features/06-event-crud-test.plan.md`
- Test Pyramid 정의 (L1:52, L2:6, L3:18, L4:4)
- Kotest 6.1.0 + MockK 1.14.9 + springmockk 5.0.1 기술 선정
- DDD 관점 테스트 철학 정의
