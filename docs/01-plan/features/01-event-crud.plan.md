# Event CRUD Planning Document

> **Summary**: 선착순 이벤트의 기반이 되는 DDD 기반 이벤트 엔티티 설계와 관리자용 CRUD/검색 API 구현
>
> **Project**: spring-event-lab
> **Version**: 0.0.1-SNAPSHOT
> **Author**: beomjin
> **Date**: 2026-04-09 (최초) / 2026-04-10 (v0.4 갱신)
> **Status**: In Progress
> **Roadmap**: [flash-sale.plan.md](flash-sale.plan.md) (1/5)

---

## Executive Summary

| Perspective | Content |
|-------------|---------|
| **Problem** | 선착순 발급 시스템의 기반이 되는 이벤트(쿠폰/티켓) 도메인 모델과 관리/검색 API가 없음 |
| **Solution** | Rich Domain Model + DateRange Value Object 기반 JPA Entity, QueryDSL 동적 검색, 1-based Pagination을 갖춘 REST API 제공 |
| **Function/UX Effect** | 관리자가 이벤트를 생성/조회/검색하고 상태(READY/OPEN/CLOSED)와 시간 기반 기간(UPCOMING/ONGOING/ENDED)을 함께 필터링 가능 |
| **Core Value** | DDD 원칙(Aggregate 경계, Entity 불변식, Value Object) + 도메인별 ErrorCode 체계 + 표준 Pagination으로 후속 feature(redis-stock, kafka-consumer)의 아키텍처 기준 확립 |

---

## 1. Overview

### 1.1 Purpose

선착순 쿠폰/티켓 발급 시스템의 기반이 되는 **Event 도메인**을 DDD 관점에서 설계한다.
이벤트 생성, 목록 검색(다중 필터 + 페이징 + 정렬), 상세 조회 CRUD를 먼저 구현하여 이후 Redis/Kafka 연동의 토대를 만든다.

### 1.2 학습 포인트

- **Rich Domain Model**: 도메인 로직을 Entity 내부에 캡슐화 (`issue()`, `open()`, `close()`) — Anemic Domain Model 안티패턴 회피
- **Value Object 패턴**: `DateRange` `@Embeddable`로 "기간" 개념 추출 및 불변식 캡슐화
- **Aggregate 경계**: Event ↔ CouponIssue는 `@ManyToOne`이 아닌 **ID 참조**만 사용 (DDD 원칙 + 선착순 환경 성능 최적화)
- **UUID v7 ID 전략**: `UuidCreator.getTimeOrderedEpoch()` + `@JdbcTypeCode(SqlTypes.UUID)`
- **ErrorCode 컨벤션**: `{DOMAIN}_{CONDITION}` 패턴 통일 + 원인별 서브코드(`E409-1`, `E409-2`)
- **QueryDSL 동적 검색**: `BooleanExpression?` null-safe 필터 + `PageableExecutionUtils` lazy count
- **Spring Native Pagination**: `@PageableDefault` + `@ParameterObject` + `one-indexed-parameters: true`로 1-based API
- **Flyway (Spring Boot 4)**: `spring-boot-starter-flyway` 사용 (auto-config이 별도 모듈로 분리됨)

### 1.3 DDD 작업 원칙 (프로젝트 공통)

> 자세한 내용은 `CLAUDE.md` 참조.
>
> Aggregate 경계 · Entity 불변식 · Value Object 추출 · 도메인 로직의 Entity 내부 캡슐화(Rich Domain Model)를 우선한다. Service는 유스케이스 orchestration 역할로 얇게 유지한다.

---

## 2. Scope

### 2.1 In Scope

- [x] Event 엔티티 설계 (title, totalQuantity, issuedQuantity, eventStatus, period: DateRange)
- [x] DateRange Value Object (`@Embeddable`) — `startedAt`, `endedAt` + 불변식
- [x] EventStatus enum (READY/OPEN/CLOSED) + `isIssuable` + 상태 전이 규칙(`canTransitionTo`, `transitionTo`)
- [x] CouponIssue 엔티티 (eventId: UUID, userId: UUID) — **ID 참조만**, `@ManyToOne` 없음
- [x] Flyway 마이그레이션 (event, coupon_issue)
- [x] Event CRUD API (생성, 검색 목록, 상세 조회)
- [x] 검색 조건 DTO (`EventSearchCond`): keyword + searchType + statuses + period + createdFrom/To + hasRemainingStock
- [x] 검색어 매핑 enum (`EventSearchType`): TITLE (확장 여지)
- [x] 기간 필터 enum (`EventPeriod`): UPCOMING/ONGOING/ENDED
- [x] QueryDSL `EventQuery` object — where 표현식 + 정렬 변환
- [x] QueryDSL `EventQueryRepository` — 검색 + lazy count
- [x] 도메인 전용 ErrorCode: `EVENT_NOT_FOUND`, `EVENT_NOT_OPEN`, `EVENT_OUT_OF_STOCK`, `EVENT_INVALID_STATUS_TRANSITION`
- [x] 공통 ErrorCode: `INVALID_DATE_RANGE` (DateRange 재사용 고려)
- [x] Request/Response DTO + Bean Validation (`@field:` target)
- [x] Swagger OpenAPI 문서 (`@Schema`, `@ParameterObject`)
- [x] Spring Boot 4 Flyway 호환 (`spring-boot-starter-flyway`)

### 2.2 Out of Scope

- 쿠폰 발급 동시성 로직 (redis-stock feature에서 구현)
- Redis 연동, Kafka 연동
- 이벤트 수정/삭제 (`PUT`, `DELETE`) — 필요 시 후속 PR
- 인증/인가
- QueryDSL Projection 최적화 (현재는 엔티티 fetch + DTO 매핑)

---

## 3. Requirements

### 3.1 Functional Requirements

| ID | Requirement | Priority | Status |
|----|-------------|----------|--------|
| FR-01 | 이벤트 생성 API: title, totalQuantity, startedAt, endedAt 입력 | High | ✅ |
| FR-02 | 이벤트 목록 조회 API: 페이징(1-based) + 다중 필터 + 정렬 | High | ✅ |
| FR-03 | 이벤트 상세 조회 API: 잔여 수량 포함 | High | ✅ |
| FR-04 | EventStatus enum 전이 규칙 (READY → OPEN → CLOSED) | High | ✅ |
| FR-05 | Flyway 마이그레이션으로 스키마 생성 | High | ✅ |
| FR-06 | 입력값 검증 (`@NotBlank`, `@Size`, `@Min`, DateRange 불변식) | High | ✅ |
| FR-07 | 다중 필터 검색 (keyword+searchType, statuses, period, createdFrom/To, hasRemainingStock) | High | ✅ |
| FR-08 | 다중 정렬 지원 (`?sort=createdAt,desc&sort=title,asc`) 및 허용 필드 화이트리스트 | Medium | ✅ |
| FR-09 | Lazy count 쿼리 (`PageableExecutionUtils`) | Medium | ✅ |
| FR-10 | 도메인별 ErrorCode 원인 분리 (`E409-1`, `E409-2`, `E409-3`) | Medium | ✅ |

### 3.2 Non-Functional Requirements

| Category | Criteria | Measurement Method |
|----------|----------|-------------------|
| Maintainability | DDD 원칙 준수 (Rich Domain Model, Aggregate 경계, Value Object) | 코드 리뷰, `CLAUDE.md` 기준 |
| Maintainability | ErrorCode 네이밍 일관성 (`{DOMAIN}_{CONDITION}`) | 코드 리뷰 |
| Documentation | Swagger UI에 모든 API/DTO/필터 노출 | `/swagger-ui.html` 접속 확인 |
| Security | SQL Injection 방지 (정렬 필드 화이트리스트, JPA parameterized query) | 코드 리뷰 |
| Performance | 첫 페이지에서 count 쿼리 스킵 가능 | 쿼리 로그 확인 |
| Extensibility | DateRange Value Object 재사용 가능 | 후속 feature에서 Coupon 유효기간 등에 적용 |

---

## 4. Domain Design (Template Convention)

> 프로젝트 전체 엔티티 규칙은 [flash-sale.plan.md - 7.2 Entity Template Convention](flash-sale.plan.md)을 따른다.
> - 생성자 파라미터로 필드를 받고, body에서 `var ... = param` + `protected set`
> - `@Column`에 `nullable`, `length`, `comment` 등 상세 속성 명시
> - `BaseTimeEntity()` 상속 (createdAt, updatedAt 자동 관리)
> - ID: `UuidCreator.getTimeOrderedEpoch()` (UUID v7) + `@JdbcTypeCode(SqlTypes.UUID)`
> - 시간 타입: `Instant` 사용 (타임존 독립적 UTC 시각)
> - 도메인 불변식은 Entity `init` 블록 또는 **Value Object** 내부에서 검증

### 4.1 Event Entity (Rich Domain Model)

```kotlin
@Entity
@Table(name = "event")
class Event(
    title: String,
    totalQuantity: Int,
    eventStatus: EventStatus,
    period: DateRange,   // ← Value Object로 캡슐화
) : BaseTimeEntity() {

    @Id
    @JdbcTypeCode(SqlTypes.UUID)
    var id: UUID = UuidCreator.getTimeOrderedEpoch()
        protected set

    @Column(nullable = false, length = 200, comment = "이벤트 제목")
    var title: String = title
        protected set

    @Column(nullable = false, comment = "총 수량")
    var totalQuantity: Int = totalQuantity
        protected set

    @Column(nullable = false, comment = "발급된 수량")
    var issuedQuantity: Int = 0
        protected set

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20, comment = "이벤트 상태")
    var eventStatus: EventStatus = eventStatus
        protected set

    @Embedded
    var period: DateRange = period
        protected set

    // --- 도메인 로직 ---
    val remainingQuantity: Int get() = totalQuantity - issuedQuantity
    fun isIssuable(): Boolean = eventStatus.isIssuable && remainingQuantity > 0

    fun issue() {
        if (!eventStatus.isIssuable) {
            throw BusinessException(ErrorCode.EVENT_NOT_OPEN, "status=$eventStatus")
        }
        if (remainingQuantity <= 0) {
            throw BusinessException(
                ErrorCode.EVENT_OUT_OF_STOCK,
                "total=$totalQuantity, issued=$issuedQuantity",
            )
        }
        issuedQuantity++
    }

    fun open() { eventStatus = eventStatus.transitionTo(EventStatus.OPEN) }
    fun close() { eventStatus = eventStatus.transitionTo(EventStatus.CLOSED) }
}
```

### 4.2 DateRange Value Object (`@Embeddable`)

```kotlin
@Embeddable
class DateRange(
    startedAt: Instant,
    endedAt: Instant,
) {
    @Column(name = "started_at", nullable = false)
    var startedAt: Instant = startedAt
        protected set

    @Column(name = "ended_at", nullable = false)
    var endedAt: Instant = endedAt
        protected set

    init {
        // 불변식: startedAt < endedAt
        if (!startedAt.isBefore(endedAt)) {
            throw BusinessException(
                ErrorCode.INVALID_DATE_RANGE,
                "startedAt=$startedAt, endedAt=$endedAt",
            )
        }
    }

    fun contains(instant: Instant): Boolean =
        !instant.isBefore(startedAt) && instant.isBefore(endedAt)
    fun isUpcoming(now: Instant = Instant.now()): Boolean = now.isBefore(startedAt)
    fun isOngoing(now: Instant = Instant.now()): Boolean = contains(now)
    fun isEnded(now: Instant = Instant.now()): Boolean = !endedAt.isAfter(now)
}
```

### 4.3 EventStatus Enum (상태 전이 + `isIssuable`)

```kotlin
enum class EventStatus(
    val description: String,
    val isIssuable: Boolean,
    private val allowedTransitions: () -> Set<EventStatus>,
) {
    READY("이벤트 준비 중 (시작 전)", isIssuable = false, { setOf(OPEN) }),
    OPEN("이벤트 진행 중 (발급 가능)", isIssuable = true, { setOf(CLOSED) }),
    CLOSED("이벤트 종료", isIssuable = false, { emptySet() }),
    ;

    fun canTransitionTo(next: EventStatus): Boolean = next in allowedTransitions()

    fun transitionTo(next: EventStatus): EventStatus {
        if (!canTransitionTo(next)) {
            throw BusinessException(
                ErrorCode.EVENT_INVALID_STATUS_TRANSITION,
                "[$this → $next] 허용된 전환: ${allowedTransitions()}",
            )
        }
        return next
    }
}
```

### 4.4 CouponIssue Entity (별도 Aggregate, ID 참조만)

```kotlin
@Entity
@Table(name = "coupon_issue")
class CouponIssue(
    eventId: UUID,   // ← @ManyToOne Event 아니고 UUID!
    userId: UUID,
) : BaseCreatedTimeEntity() {
    @Id
    @JdbcTypeCode(SqlTypes.UUID)
    var id: UUID = UuidCreator.getTimeOrderedEpoch()
        protected set

    @Column(nullable = false, comment = "이벤트 ID")
    var eventId: UUID = eventId
        protected set

    @Column(nullable = false, comment = "사용자 ID")
    var userId: UUID = userId
        protected set
}
```

**왜 `@ManyToOne`을 쓰지 않는가?**
- **DDD Aggregate 경계 준수**: Event와 CouponIssue는 각각 독립된 Aggregate Root. Vaughn Vernon의 원칙 "Reference other aggregates by identity only"
- **선착순 성능 최적화**: 발급 1건당 불필요한 Event SELECT(N+1)를 피함
- **분리 가능성**: 향후 CouponIssue를 별도 DB/샤드로 옮기기 쉬움

---

## 5. Search Condition & Filter Enums

### 5.1 EventSearchCond (요청 DTO)

| Field | Type | 설명 |
|-------|------|------|
| `keyword` | `String?` | 검색어 (최소 2자) |
| `searchType` | `EventSearchType` | 검색어 매핑 필드 (기본: `TITLE`) |
| `statuses` | `List<EventStatus>?` | 상태 다중 필터 |
| `period` | `EventPeriod?` | 시간 기반 기간 필터 |
| `createdFrom` | `Instant?` | 생성일 범위 시작 |
| `createdTo` | `Instant?` | 생성일 범위 종료 |
| `hasRemainingStock` | `Boolean?` | 재고 보유 여부 (true/false/null) |

페이지/크기/정렬은 별도 Spring `Pageable`로 받는다 (`@PageableDefault` + `@ParameterObject`).

### 5.2 EventSearchType Enum

```kotlin
enum class EventSearchType(val description: String) {
    TITLE("이벤트 제목"),
    // 추후 ID, DESCRIPTION 등으로 확장 가능
}
```

### 5.3 EventPeriod Enum

```kotlin
enum class EventPeriod(val description: String) {
    UPCOMING("예정 (시작 전)"),
    ONGOING("진행 중 (시작됨, 종료 전)"),
    ENDED("종료됨"),
}
```

`EventStatus`(수동 전환)와 달리 `EventPeriod`는 **현재 시각 기준 자동 판정**이다. 둘은 독립 조합 가능.

---

## 6. Flyway Migration (DDL)

> 파일 위치: `src/main/resources/db/migration/`
> JPA `ddl-auto: validate` — Flyway가 DDL 관리, JPA는 검증만
> **중요**: Spring Boot 4에서는 `spring-boot-starter-flyway` 필수 (auto-config이 별도 모듈로 분리됨)

### 6.1 V20260409174330__create_event_table.sql

```sql
CREATE TABLE event
(
    id              UUID PRIMARY KEY,
    title           VARCHAR(200) NOT NULL,
    total_quantity  INT          NOT NULL,
    issued_quantity INT          NOT NULL DEFAULT 0,
    event_status    VARCHAR(20)  NOT NULL DEFAULT 'READY',
    started_at      TIMESTAMP    NOT NULL,   -- @Embedded DateRange.startedAt
    ended_at        TIMESTAMP    NOT NULL,   -- @Embedded DateRange.endedAt
    created_at      TIMESTAMP    NOT NULL DEFAULT NOW(),
    updated_at      TIMESTAMP    NOT NULL DEFAULT NOW()
);
```

> `DateRange`를 `@Embeddable`로 도입해도 **DB 스키마는 동일하다**. `@Column(name = "started_at")`, `@Column(name = "ended_at")`이 DateRange 내부에 선언되어 기존 컬럼에 그대로 매핑된다.

### 6.2 V20260409174359__create_coupon_issue_table.sql

```sql
CREATE TABLE coupon_issue
(
    id         UUID PRIMARY KEY,
    event_id   UUID      NOT NULL REFERENCES event (id),
    user_id    UUID      NOT NULL,
    created_at TIMESTAMP NOT NULL DEFAULT NOW(),
    CONSTRAINT uk_coupon_issue UNIQUE (event_id, user_id)
);

CREATE INDEX idx_coupon_issue_event_id ON coupon_issue (event_id);
CREATE INDEX idx_coupon_issue_user_id ON coupon_issue (user_id);
```

---

## 7. Package Structure

```
src/main/kotlin/com/beomjin/springeventlab/
├── coupon/
│   ├── controller/
│   │   └── EventController.kt
│   ├── dto/
│   │   ├── request/
│   │   │   ├── EventCreateRequest.kt
│   │   │   ├── EventSearchCond.kt
│   │   │   ├── EventSearchType.kt
│   │   │   └── EventPeriod.kt
│   │   └── response/
│   │       └── EventResponse.kt
│   ├── entity/
│   │   ├── Event.kt
│   │   ├── EventStatus.kt
│   │   └── CouponIssue.kt
│   ├── repository/
│   │   ├── EventRepository.kt           # JpaRepository<Event, UUID>
│   │   └── EventQueryRepository.kt      # QueryDSL + PageableExecutionUtils
│   └── service/
│       ├── EventQuery.kt                # QueryDSL 표현식 모음
│       └── EventService.kt              # 유스케이스 orchestration
└── global/
    ├── common/
    │   ├── BaseTimeEntity.kt
    │   ├── BaseCreatedTimeEntity.kt
    │   ├── DateRange.kt                 # NEW @Embeddable Value Object
    │   └── PageResponse.kt
    ├── config/
    │   ├── JpaConfig.kt                 # JPAQueryFactory bean
    │   └── SwaggerConfig.kt
    └── exception/
        ├── ErrorCode.kt                 # EVENT_* + INVALID_DATE_RANGE
        ├── ErrorResponse.kt
        ├── BusinessException.kt
        └── GlobalExceptionHandler.kt
```

---

## 8. API Design

| Method | Endpoint | Description | Response |
|--------|----------|-------------|----------|
| POST | `/api/v1/events` | 이벤트 생성 | `EventResponse` (201) |
| GET | `/api/v1/events` | 검색 목록 (다중 필터 + 페이징 + 정렬) | `PageResponse<EventResponse>` (200) |
| GET | `/api/v1/events/{id}` | 이벤트 상세 조회 | `EventResponse` (200) |

### Query Parameters (GET /api/v1/events)

| Param | Type | Default | Description |
|-------|------|---------|-------------|
| `keyword` | string | - | 검색어 (최소 2자) |
| `searchType` | `EventSearchType` | `TITLE` | 검색어 매핑 필드 |
| `statuses` | `EventStatus[]` | - | 상태 다중 필터 |
| `period` | `EventPeriod` | - | 시간 기반 기간 필터 |
| `createdFrom` | `Instant` | - | 생성일 시작 |
| `createdTo` | `Instant` | - | 생성일 종료 |
| `hasRemainingStock` | `Boolean` | - | 재고 보유 필터 |
| `page` | int | 1 | **1-based** 페이지 번호 |
| `size` | int | 20 | 페이지 크기 (max 100) |
| `sort` | string | `createdAt,desc` | 다중 정렬 (`?sort=title,asc&sort=createdAt,desc`) |

---

## 9. ErrorCode Convention

**패턴**: `{DOMAIN}_{CONDITION}` (공통 에러는 prefix 없음)

| Code | HTTP | 의미 | 발생 지점 |
|------|------|------|---------|
| `INVALID_INPUT` | 400 | 잘못된 입력 (Bean Validation) | GlobalExceptionHandler |
| `INVALID_DATE_RANGE` | 400 | 기간 불변식 위반 (`startedAt >= endedAt`) | `DateRange` init |
| `EVENT_NOT_FOUND` | 404 | 이벤트 미존재 | `EventService.getEvent` |
| `EVENT_NOT_OPEN` (`E409-1`) | 409 | 진행 중인 이벤트 아님 | `Event.issue` |
| `EVENT_OUT_OF_STOCK` (`E409-2`) | 409 | 재고 소진 | `Event.issue` |
| `EVENT_INVALID_STATUS_TRANSITION` (`E409-3`) | 409 | 상태 전환 불가 | `EventStatus.transitionTo` |

**서브코드 전략**: 같은 HTTP 상태에서 원인별 분리가 필요할 때 `E409-1`, `E409-2` 식으로 확장. 클라이언트는 원인별 UX 분기가 가능해짐.

---

## 10. Success Criteria

- [x] Flyway 마이그레이션이 정상 실행된다 (`spring-boot-starter-flyway` 포함)
- [x] Entity가 Template Convention을 따르고 DDD 원칙을 반영한다
- [x] DateRange 불변식이 Entity 구성 시점에 자동 검증된다
- [x] CRUD/검색 API가 Swagger UI에서 테스트 가능하다
- [x] 다중 필터 + 1-based 페이징 + 다중 정렬이 동작한다
- [x] ErrorCode가 `{DOMAIN}_{CONDITION}` 패턴을 따른다
- [x] Event ↔ CouponIssue 간 `@ManyToOne`이 존재하지 않는다 (DDD Aggregate 경계)
- [x] `CLAUDE.md`에 DDD 작업 원칙이 명시된다
- [ ] 통합 테스트(Testcontainers + MockMvc) 추가
- [ ] Gap 분석(`/pdca analyze event-crud`) 90% 이상

---

## 11. Next Steps

1. [ ] Docker Compose로 앱 실행 및 Swagger UI 수동 검증
2. [ ] 통합 테스트 작성 (Happy path + Validation + Filter 시나리오)
3. [ ] `/pdca analyze event-crud`로 Gap 분석
4. [ ] 다음 feature: **`redis-stock`** — 발급 동시성 처리

---

## Version History

| Version | Date | Changes | Author |
|---------|------|---------|--------|
| 0.1 | 2026-04-09 | Initial draft | beomjin |
| 0.2 | 2026-04-09 | UUID v7 ID 전략 적용 | beomjin |
| 0.3 | 2026-04-10 | 실제 구현 엔티티에 맞춰 동기화 | beomjin |
| **0.4** | **2026-04-10** | **DDD 리팩토링 반영**: DateRange Value Object, EventStatus.isIssuable + 전이 규칙, 원인별 ErrorCode 서브코드(`E409-*`), `{DOMAIN}_{CONDITION}` 네이밍 통일, EventSearchCond 다중 필터 + EventSearchType/EventPeriod enum, Spring 네이티브 Pageable(1-based), QueryDSL EventQuery + EventQueryRepository, Spring Boot 4 `spring-boot-starter-flyway` 필수 사항, CLAUDE.md DDD 원칙 문서화 | beomjin |
