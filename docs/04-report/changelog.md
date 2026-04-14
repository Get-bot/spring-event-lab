# Changelog

All notable changes to this project will be documented in this file.

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
