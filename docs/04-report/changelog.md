# Changelog

All notable changes to this project will be documented in this file.

---

## [2026-04-25] - Waiting Queue Feature Complete (Flash Sale Roadmap 5/5)

### Added
- **Waiting Queue 시스템 구현 완료** — Redis Sorted Set 기반 대기열, Scheduler 배치 처리 (600 lines)
  - Lua 원자성: enter_queue.lua — SISMEMBER + ZSCORE + ZADD + EXPIRE 1 RTT 처리
  - WaitingQueueRepository: tryEnter / rank / size / popMin / recordResult / findResult
  - WaitingQueueService: enter(이벤트 검증 + Lua + rank 반환) / status(결과 폴링 지원)
  - CouponIssueScheduler: @Scheduled(fixedDelay) drain + SOLD_OUT short-circuit
  - WaitingQueueController: POST /enter, GET /queue/status (6가지 status enum)
  - SchedulingConfig: @EnableScheduling + TaskScheduler (poolSize=2, graceful shutdown)
  - WaitingQueueProperties: poll-interval-ms / batch-size / result-ttl-seconds 운영자 제어

### Methodology
- **Backpressure 패턴**: 거부(429) 아닌 지연(200 + rank) — 시스템 처리 능력과 트래픽 분리
- **Lua 원자성**: SISMEMBER/ZSCORE/ZADD/EXPIRE를 1 RTT 안에 race-free 처리
- **결과 비동기 통지**: ZPOPMIN은 destructive → result:{eventId}:{userId} 키에 TTL 기록
- **SOLD_OUT short-circuit**: batch 내 첫 매진 후 잔여 유저는 Lua 미호출, SET만으로 일괄 통보
- **fixedDelay**: 처리 시간 증가 시 자동 back-pressure (호출 누적 방지)
- **기존 경로 재사용**: redis-stock(Lua) + kafka-consumer(Producer 보상) 100% 재사용

### Verified
- **Match Rate**: 99.0% (설계 50/50 구현 일치, P0/P1 없음, P2 3건 모두 design 문서 표기 오류)
- **Convention Compliance**: 6/6 = 100% (BusinessException, @Schema, findByIdOrNull, DDD, hash tags, errorcode)
- **Implementation Order**: 11/11 완료 (Lua→Redis→Repository→Service→Controller→Scheduler→Config)
- **API Contract**: 2 endpoints (POST /enter, GET /queue/status) 구현 + 에러 매트릭 12개 경로
- **Design Items**: 50/50 = 100% (아키텍처, 데이터 흐름, 시퀀스, 에러 매트릭, 파일 구조)

### Documentation
- **Completion Report**: `docs/04-report/features/05-waiting-queue.report.md` (v1.0)
- **Gap Analysis**: `docs/03-analysis/05-waiting-queue.analysis.md` (Match Rate 99.0%)
- **Design Document**: `docs/02-design/features/05-waiting-queue.design.md` (v0.1, 2026-04-25)
- **Planning Document**: `docs/01-plan/features/05-waiting-queue.plan.md` (v0.1, 2026-04-09)

### Learning
- **L1**: Design 문서 자체가 자기 모순(§2.5 vs §3.8)을 가질 수 있다 — design-validator 필요
- **L2**: Queue는 rate limiter가 아니라 버퍼 — 거부 대신 지연 허용으로 공정성 + 진행 상황 가시화
- **L3**: ZPOPMIN의 destructive 특성 때문에 결과 통지 키 별도 도입 — crash 복구는 Redis Streams가 다음 phase
- **L4**: 학습 프로젝트의 정직한 OOS 명시 (§11 5개 항목) — "완벽함" 대신 "학습 적층"
- **L5**: Flash Sale Roadmap 5/5 완성 — CRUD(DDD) → Stock(Lua) → Concurrency(test) → Kafka(비동기) → Queue(backpressure)의 적층

### Roadmap Progress
- **Flash Sale 완료**: 5/5 (Event CRUD + Redis Stock + Concurrency Test + Kafka Consumer + **Waiting Queue**)
- **누적 Line**: ~4,500 (Entities 500 + Services 1200 + Controllers 400 + Tests 1000 + Config 400 + Lua 100)
- **누적 PDCA**: 6 cycles (모두 ≥90% match rate 달성)

### Follow-up Work
- **테스트 작성**: design §9 Testing Strategy (L2~L4, 별도 PR)
- **Design 문서 수정**: P2 3건 (§2.5 메서드명, §3.8 인터페이스, §3.4 캐싱 note) — fast-track
- **다음 학습 Phase**: Redis Streams(crash 복구) / ShedLock(분산 락) / Caffeine(캐싱) / SSE(결과 푸시)

---

## [2026-04-25] - Event CRUD Feature Complete (Flash Sale Roadmap 1/5)

### Added
- **Event CRUD API 구현 완료** — DDD Rich Domain Model + QueryDSL 동적 검색 (2,500 lines)
  - Event entity: Rich Domain Model (issue/open/close 메서드, 상태 불변식)
  - DateRange Value Object: @Embeddable, startedAt < endedAt 불변식 자동 검증
  - EventStatus enum: 상태 전이 규칙 (READY→OPEN→CLOSED)
  - CouponIssue entity: ID 참조만 (DDD Aggregate 경계, @ManyToOne 미사용)
  - EventQueryRepository: QueryDSL 동적 필터 + PageableExecutionUtils lazy count
  - EventController: 다중 필터 검색 + 1-based Pagination + 다중 정렬

### Methodology
- **DDD 원칙**: Rich Domain Model로 도메인 로직 Entity 내부 캡슐화 (Anemic 회피)
- **Value Object 추출**: DateRange로 기간 개념 한 곳에서 관리, Coupon/Promotion 재사용 가능
- **Aggregate 경계**: Event ↔ CouponIssue ID 참조만 (성능 + 분리 가능성)
- **QueryDSL null-safe**: listOfNotNull + 화이트리스트 정렬로 동적 검색 안전성
- **ErrorCode 세분화**: {DOMAIN}_{CONDITION} 패턴 + E409-1/-2/-3 서브코드로 원인별 분리
- **1-based Pagination**: Spring 네이티브 one-indexed-parameters (커스텀 프레임워크 미필요)

### Verified
- **Match Rate**: 95% (Design 일치 38/40, 의도적 개선 3, 문서 갱신 2)
- **DDD Convention**: 14/14 = 100% (Aggregate·Value Object·Entity 불변식·ErrorCode·QueryDSL 등)
- **Success Criteria**: 10/10 (Flyway·Entity template·DateRange·API·다중 필터·ErrorCode·Aggregate·CLAUDE.md·테스트·Gap)
- **Test Coverage**: 6 layer 완전 커버 (Entity/DTO/Repository/Service/Controller/Integration)
- **Implementation Order**: 13/13 완료

### Documentation
- **Completion Report**: `docs/04-report/features/01-event-crud.report.md` (v1.0)
- **Gap Analysis**: `docs/03-analysis/01-event-crud.analysis.md` (Retroactive Check, Match Rate 95%)
- **Plan/Design**: v0.4 완료 (DDD 리팩토링 반영, 9개 설계 결정 정의)

### Learning
- **L1**: DDD Rich Domain Model이 코드 유지보수성을 극대화 — 규칙이 data 소유 엔티티에 모임
- **L2**: Value Object는 두 번째 사용처에서 가치 증명 — DateRange는 Coupon, Promotion 등에 재사용 가능하게 설계
- **L3**: Aggregate 경계 (ID 참조)가 선착순 성능 최적화의 핵심 — N+1 방지 + 향후 샤드 가능
- **L4**: QueryDSL null-safe 필터가 동적 검색을 선언적으로 구현 — 복잡한 if 문 제거
- **L5**: ErrorCode 서브코드가 클라이언트 원인별 UX 분기 가능하게 함 — E409-2(매진)vs E409-1(상태 불일치)
- **L6**: Spring Boot 4 Flyway 호환성 — spring-boot-starter-flyway 필수 (auto-config 분리)
- **Roadmap Progress**: Flash Sale 1/5 완성, baseline architecture 확립

### Archive Status
- **Ready for Archive**: YES (Match Rate 95%, DDD 14/14, functional gap 0)
- **Pending Updates** (archive 후 선택): G1·G2·G3 문서 갱신 (5-10분, 경미한 수준)
- **Archive Path**: `docs/archive/2026-04/01-event-crud/`

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
