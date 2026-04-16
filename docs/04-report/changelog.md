# Changelog

All notable changes to this project will be documented in this file.

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
