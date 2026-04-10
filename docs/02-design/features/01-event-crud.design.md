# Event CRUD Design Document

> **Summary**: 선착순 이벤트 도메인의 DDD 기반 엔티티 설계와 관리자용 CRUD/검색 API의 상세 기술 명세
>
> **Project**: spring-event-lab
> **Version**: 0.0.1-SNAPSHOT
> **Author**: beomjin
> **Date**: 2026-04-09 (최초) / 2026-04-10 (v0.4 갱신)
> **Status**: In Progress
> **Planning Doc**: [01-event-crud.plan.md](../../01-plan/features/01-event-crud.plan.md)

---

## Executive Summary

| Perspective | Content |
|-------------|---------|
| **Problem** | 선착순 발급 시스템의 기반이 되는 이벤트 도메인 모델, 다중 필터 검색 API, 도메인 예외 체계가 없어 후속 기능(Redis, Kafka) 개발이 불가능 |
| **Solution** | DDD Rich Domain Model(Event + DateRange Value Object + EventStatus 전이) + QueryDSL 동적 검색 + Spring 네이티브 1-based Pageable + 원인별 ErrorCode 체계로 REST API 제공 |
| **Function/UX Effect** | 관리자가 이벤트를 생성/조회/검색(keyword, status, period, 생성일 범위, 재고 유무)하고 상태 전이(READY→OPEN→CLOSED)를 안전하게 수행 가능 |
| **Core Value** | 프로젝트 전체의 DDD 원칙(Aggregate 경계·Value Object·Entity 불변식) + ErrorCode 컨벤션 + Pagination 패턴을 확립해 후속 feature의 아키텍처 기준이 된다 |

---

## 1. Overview

### 1.1 Design Goals

- **DDD 원칙 준수**: Rich Domain Model, Value Object(DateRange), Aggregate 경계(Event ↔ CouponIssue ID 참조), Entity 불변식 자동 검증
- **확장 가능한 검색**: `BooleanExpression?` null-safe 필터 + `PageableExecutionUtils` lazy count + 다중 정렬
- **명시적 에러 코드**: `{DOMAIN}_{CONDITION}` 패턴 + 원인별 서브코드(`E409-*`)로 클라이언트가 원인별 UX 분기 가능
- **Spring 관용구**: 커스텀 Pageable/검색 프레임워크 없이 Spring Data + QueryDSL + `@PageableDefault` + `@ParameterObject`만 사용
- **Spring Boot 4 호환**: `spring-boot-starter-flyway`로 auto-config 트리거 (flyway-core 단독 사용 시 실패)

### 1.2 Design Principles

- **Rich Domain Model**: 도메인 로직은 Entity/Enum 내부에 캡슐화 — Anemic Domain Model(로직이 Service에 쌓이는 구조)은 지양
- **Value Object 추출**: 같은 쌍으로 다니는 필드는 `@Embeddable` Value Object로 묶어 불변식을 한 곳에서 보장
- **Aggregate 경계 준수**: 다른 Aggregate Root는 ID로만 참조 (Vaughn Vernon의 원칙) → 선착순 환경에서 N+1 방지
- **Fail-fast Validation**: 계층별 방어선 (DTO Bean Validation → Entity/Value Object 불변식)
- **Layered Architecture**: Controller → Service → (EventRepository | EventQueryRepository) 단방향 의존
- **CLAUDE.md 작업 원칙**: DDD 관점에서 작업 (문서화 완료)

---

## 2. Architecture

### 2.1 Component Diagram

```
┌──────────────┐     ┌──────────────────────────────────────────────────┐     ┌──────────────┐
│              │     │                Spring Boot App                   │     │              │
│   Client     │────▶│  EventController (@ParameterObject EventSearch)  │────▶│  PostgreSQL  │
│ (Swagger UI) │     │      ↓                                           │     │  (eventlab)  │
│              │     │  EventService                                    │     │              │
│              │     │      ↓            ↓                              │     │              │
│              │     │  EventRepository  EventQueryRepository (QueryDSL) │     │              │
│              │     │      ↓                  ↓                         │     │              │
│              │     │  Event (@Entity) ← @Embedded DateRange            │     │              │
│              │     │      ↑                                           │     │              │
│              │     │   EventStatus (enum + 전이 규칙)                 │     │              │
│              │     │                                                  │     │              │
│              │◀────│   GlobalExceptionHandler (ErrorCode → ErrorResponse)    │              │
│              │     └──────────────────────────────────────────────────┘     │              │
└──────────────┘                                                                └──────────────┘
```

### 2.2 Data Flow

```
[POST /api/v1/events — 이벤트 생성]
Client Request → Bean Validation (@field:NotBlank/Size/Min/NotNull)
  → EventController.create(EventCreateRequest)
  → EventService.create(request)
  → request.toEntity() → new DateRange(start, end) [← 불변식 검증]
                       → new Event(title, qty, READY, dateRange)
  → EventRepository.save(event)
  → EventResponse.from(event) → 201 Created

[GET /api/v1/events — 검색 목록]
Client Request → Bean Validation + Type binding
  → EventController.getEvents(@ParameterObject @Valid EventSearchCond,
                              @PageableDefault Pageable)
  → EventService.getEvents(cond, pageable)
  → EventQueryRepository.search(cond, pageable)
      → listOfNotNull(EventQuery.keywordMatches, statusesIn, periodMatches,
                      createdBetween, hasRemainingStock).toTypedArray()
      → queryFactory.selectFrom(event).where(*conditions).orderBy(*orders).fetch()
      → PageableExecutionUtils.getPage(content, pageable) { count 쿼리 }   ← lazy
  → page.map(EventResponse::from)
  → PageResponse.from(...) → 200 OK

[GET /api/v1/events/{id} — 상세 조회]
Client Request → EventController.getEvent(id: UUID)
  → EventService.getEvent(id)
  → eventRepository.findByIdOrNull(id) ?: throw BusinessException(EVENT_NOT_FOUND)
  → EventResponse.from(event) → 200 OK / 404 EVENT_NOT_FOUND
```

### 2.3 Dependencies

| 신규 컴포넌트 | 의존하는 기존 컴포넌트 | 용도 |
|---------------|----------------------|------|
| `Event` entity | `global.common.BaseTimeEntity` | createdAt, updatedAt 자동 관리 |
| `Event` entity | `global.common.DateRange` | **NEW** — 기간 Value Object |
| `CouponIssue` entity | `global.common.BaseCreatedTimeEntity` | createdAt만 필요 |
| `DateRange` | `global.exception.BusinessException`, `ErrorCode.INVALID_DATE_RANGE` | 불변식 위반 예외 |
| `EventService` | `EventRepository`, `EventQueryRepository` | JPA + QueryDSL 리포지토리 조합 |
| `EventQueryRepository` | `global.config.JpaConfig.jpaQueryFactory` | JPAQueryFactory bean |
| `EventQueryRepository` | `PageableExecutionUtils` | lazy count 최적화 |
| `EventController` | `global.common.PageResponse` | 페이징 응답 래핑 |
| `EventCreateRequest` | Bean Validation (`@field:*`) | 입력값 형식 검증 |
| (자동) | `spring-boot-starter-flyway` | **Spring Boot 4에서 필수** — flyway-core만으로는 auto-config X |
| (자동) | `springdoc-openapi-starter-webmvc-ui` | Swagger UI, `@ParameterObject` 지원 |
| (외부) | `com.github.f4b6a3:uuid-creator` | UUID v7 생성 |

---

## 3. Data Model

### 3.1 Event Entity (Rich Domain Model)

> Convention: 생성자 파라미터 + `protected set` var 필드
> ID: UUID v7 (`UuidCreator.getTimeOrderedEpoch()` + `@JdbcTypeCode(SqlTypes.UUID)`)
> 시간 타입: `Instant` (UTC)
> `BaseTimeEntity()` 상속 → createdAt, updatedAt 자동
> **기간**: `startedAt`/`endedAt`을 직접 갖지 않고 `@Embedded period: DateRange`로 캡슐화

```kotlin
package com.beomjin.springeventlab.coupon.entity

@Entity
@Table(name = "event")
class Event(
    title: String,
    totalQuantity: Int,
    eventStatus: EventStatus,
    period: DateRange,
) : BaseTimeEntity() {

    @Id
    @JdbcTypeCode(SqlTypes.UUID)
    @Column(updatable = false, nullable = false, comment = "이벤트 PK")
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

**학습 포인트: 왜 Rich Domain Model인가?**
> Service에서 `event.eventStatus = OPEN`처럼 직접 수정하면 상태 전이 규칙을 매번 Service에서 검증해야 한다. Entity 내부에 `open()`, `close()`, `issue()`를 두면 **도메인 규칙이 한 곳**에 모여 유지보수가 쉬워진다. Anemic Domain Model(로직이 Service에 쌓이는 구조)은 DDD 안티패턴이다.

### 3.2 DateRange Value Object (`@Embeddable`)

> 위치: `global/common/DateRange.kt`
> 재사용 예정: Coupon 유효기간, Promotion 기간 등

```kotlin
package com.beomjin.springeventlab.global.common

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
        // 도메인 불변식: startedAt < endedAt
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

**학습 포인트: Value Object 추출 기준**
> 항상 쌍으로 의미를 가지는 필드(startedAt, endedAt)는 Value Object 후보다. 추출하면:
> 1. **불변식 검증 위치 단일화**: "시작 < 종료" 규칙이 DateRange 내부에만 존재
> 2. **도메인 행동 집중**: `isOngoing()`, `contains()` 등이 DateRange에 모임
> 3. **재사용**: Coupon, Promotion 등 다른 엔티티에서도 `@Embedded`로 재사용
> 4. **에러 코드 공통화**: `INVALID_DATE_RANGE`는 이벤트 전용이 아닌 공통 에러

**JPA 호환성**: `kotlin("plugin.jpa")`가 synthetic no-arg constructor를 생성하므로 `@Embeddable`도 그대로 작동한다. DB 로딩 시 reflection으로 필드를 주입하므로 init 블록은 생성 시점에만 실행된다.

### 3.3 EventStatus Enum (상태 전이 + isIssuable)

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

**학습 포인트: Enum에 행동을 부여하기**
> `isIssuable`은 "상태 자체"의 속성이므로 Enum이 책임진다. Event는 이를 `eventStatus.isIssuable && remainingQuantity > 0`로 조합한다. **관심사 분리**: 상태 레벨 판단은 Enum, 재고 레벨 판단은 Event.

### 3.4 CouponIssue Entity (별도 Aggregate)

```kotlin
@Entity
@Table(name = "coupon_issue")
class CouponIssue(
    eventId: UUID,   // @ManyToOne Event 아님!
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

**학습 포인트: 왜 `@ManyToOne`을 사용하지 않는가?**
> DDD 관점 — Event와 CouponIssue는 각각 독립된 Aggregate Root. Vaughn Vernon의 "Reference other aggregates by identity only" 원칙에 따라 **ID로만 참조**한다.
>
> 성능 관점 — 선착순 환경에서 발급 1건당 `SELECT FROM event WHERE id = ?` 쿼리가 추가되는 것은 순수 낭비다. Redis에서 재고 차감 후 Kafka consumer가 CouponIssue를 비동기 생성하는 구조에서는 **Event 엔티티가 메모리에 없어도 된다** (`eventId: UUID`만 있으면 됨).
>
> 분리 가능성 — 향후 CouponIssue를 별도 DB나 샤드로 옮기기 쉬움.

### 3.5 Flyway Migration (DDL)

**주의: Spring Boot 4 + Flyway**
> `flyway-core` 단독으로는 Spring Boot 4의 auto-configuration이 트리거되지 않는다 (4.x에서 auto-config 모듈이 분리됨). 반드시 `org.springframework.boot:spring-boot-starter-flyway`를 추가해야 한다.

#### V20260409174330__create_event_table.sql

```sql
CREATE TABLE event
(
    id              UUID PRIMARY KEY,
    title           VARCHAR(200) NOT NULL,
    total_quantity  INT          NOT NULL,
    issued_quantity INT          NOT NULL DEFAULT 0,
    event_status    VARCHAR(20)  NOT NULL DEFAULT 'READY',
    started_at      TIMESTAMP    NOT NULL,
    ended_at        TIMESTAMP    NOT NULL,
    created_at      TIMESTAMP    NOT NULL DEFAULT NOW(),
    updated_at      TIMESTAMP    NOT NULL DEFAULT NOW()
);
```

> `@Embeddable DateRange` 도입 후에도 **DB 스키마는 변경 없음**. `@Column(name = "started_at")`/`@Column(name = "ended_at")`이 Value Object 내부에 선언되어 기존 컬럼에 매핑된다.

#### V20260409174359__create_coupon_issue_table.sql

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

> `REFERENCES`는 DB 레벨 FK일 뿐, JPA 레벨에서는 `@ManyToOne`이 없다. DB는 참조 무결성만, Aggregate 경계는 코드가 보장.

---

## 4. API Specification

### 4.1 Endpoint List

| Method | Path | Description | Response |
|--------|------|-------------|----------|
| POST | `/api/v1/events` | 이벤트 생성 | `EventResponse` (201) |
| GET | `/api/v1/events` | 검색 목록 (다중 필터 + 페이징 + 정렬) | `PageResponse<EventResponse>` (200) |
| GET | `/api/v1/events/{id}` | 이벤트 상세 조회 | `EventResponse` (200) |

### 4.2 POST `/api/v1/events` — 이벤트 생성

**Request:**
```json
{
  "title": "2026년 여름 쿠폰 이벤트",
  "totalQuantity": 1000,
  "startedAt": "2026-07-01T00:00:00Z",
  "endedAt": "2026-07-07T23:59:59Z"
}
```

**Validation Rules:**
| Field | Rule | Error Code |
|-------|------|-----------|
| title | `@NotBlank`, `@Size(max=200)` | `INVALID_INPUT` |
| totalQuantity | `@NotNull`, `@Min(1)` | `INVALID_INPUT` |
| startedAt | `@NotNull` | `INVALID_INPUT` |
| endedAt | `@NotNull` | `INVALID_INPUT` |
| (cross-field) | `startedAt < endedAt` | **DateRange 생성 시 자동** → `INVALID_DATE_RANGE` |

**Response (201 Created):**
```json
{
  "id": "019644a2-3b00-7f8a-a1e2-4c5d6e7f8a9b",
  "title": "2026년 여름 쿠폰 이벤트",
  "totalQuantity": 1000,
  "issuedQuantity": 0,
  "remainingQuantity": 1000,
  "eventStatus": "READY",
  "startedAt": "2026-07-01T00:00:00Z",
  "endedAt": "2026-07-07T23:59:59Z",
  "createdAt": "2026-04-10T10:00:00Z",
  "updatedAt": "2026-04-10T10:00:00Z"
}
```

### 4.3 GET `/api/v1/events` — 검색 목록

**Query Parameters:**

| Param | Type | Default | 설명 |
|-------|------|---------|------|
| `keyword` | string | - | 검색어 (min 2자) |
| `searchType` | `EventSearchType` | `TITLE` | 검색어 매핑 필드 |
| `statuses` | `EventStatus[]` | - | 상태 다중 필터 (`?statuses=READY&statuses=OPEN`) |
| `period` | `EventPeriod` | - | 기간 필터 (`UPCOMING`/`ONGOING`/`ENDED`) |
| `createdFrom` | `Instant` | - | 생성일 시작 |
| `createdTo` | `Instant` | - | 생성일 종료 |
| `hasRemainingStock` | boolean | - | 재고 보유 여부 |
| `page` | int | 1 | **1-based** 페이지 번호 |
| `size` | int | 20 | 페이지 크기 (max 100) |
| `sort` | string | `createdAt,desc` | 다중 정렬 (`?sort=title,asc&sort=createdAt,desc`) |

**검색 예시:**
```
GET /api/v1/events
  ?keyword=여름
  &searchType=TITLE
  &statuses=READY&statuses=OPEN
  &period=ONGOING
  &createdFrom=2026-06-01T00:00:00Z
  &hasRemainingStock=true
  &page=1
  &size=20
  &sort=createdAt,desc
```

**Response (200 OK):**
```json
{
  "content": [ { "id": "...", "title": "...", "remainingQuantity": 850, "eventStatus": "OPEN", "...": "..." } ],
  "page": 0,
  "size": 20,
  "totalElements": 1,
  "totalPages": 1
}
```

> `page`가 응답에서 0으로 보이는 것은 Spring Data 내부가 0-based이기 때문이다. **클라이언트 입력은 1-based**(`one-indexed-parameters=true`)이며 내부 변환 후 JPA에서는 0-based로 사용된다.

### 4.4 GET `/api/v1/events/{id}` — 상세 조회

**Path Parameter:** `id` (UUID)

**Response (200 OK):** `EventResponse` (위와 동일)

**Error (404 EVENT_NOT_FOUND):**
```json
{
  "code": "E404",
  "message": "이벤트를 찾을 수 없습니다.",
  "errors": {},
  "timestamp": "2026-04-10T10:00:00Z"
}
```

---

## 5. DTO Specification

### 5.1 EventCreateRequest

```kotlin
package com.beomjin.springeventlab.coupon.dto.request

@Schema(description = "이벤트 생성 요청")
data class EventCreateRequest(
    @field:NotBlank(message = "이벤트 제목은 필수입니다")
    @field:Size(max = 200, message = "이벤트 제목은 200자 이하여야 합니다")
    @Schema(description = "이벤트 제목", example = "2026년 여름 쿠폰 이벤트", requiredMode = REQUIRED)
    val title: String?,

    @field:NotNull(message = "총 수량은 필수입니다")
    @field:Min(value = 1, message = "총 수량은 1 이상이어야 합니다")
    @Schema(description = "총 발급 수량 (1 이상)", example = "1000", requiredMode = REQUIRED)
    val totalQuantity: Int?,

    @field:NotNull(message = "시작 시각은 필수입니다")
    @Schema(description = "이벤트 시작 시각 (ISO-8601, UTC)", example = "2026-07-01T00:00:00Z", requiredMode = REQUIRED)
    val startedAt: Instant?,

    @field:NotNull(message = "종료 시각은 필수입니다")
    @Schema(description = "이벤트 종료 시각 (ISO-8601, UTC)", example = "2026-07-07T23:59:59Z", requiredMode = REQUIRED)
    val endedAt: Instant?,
) {
    fun toEntity(): Event = Event(
        title = title!!,
        totalQuantity = totalQuantity!!,
        eventStatus = EventStatus.READY,
        period = DateRange(startedAt!!, endedAt!!),   // ← Value Object 생성 시 불변식 검증
    )
}
```

**학습 포인트: nullable + `@field:NotNull`**
> Kotlin non-null 타입은 Jackson 바인딩 단계에서 null이면 예외가 나서 `@field:NotNull`의 커스텀 메시지가 도달하지 않는다. 특히 `Int` 같은 primitive는 JSON 누락 시 0이 들어가 `@NotNull`이 통과하는 검증 구멍이 생긴다. 따라서 **nullable 타입 + `@field:NotNull`** 조합이 정석이다. Kotlin data class는 setter가 없어도 Jackson이 생성자 기반으로 바인딩하므로 `val`로 두어도 Bean Validation이 작동한다.

### 5.2 EventSearchCond

```kotlin
package com.beomjin.springeventlab.coupon.dto.request

@Schema(description = "이벤트 목록 조회 조건")
data class EventSearchCond(
    @field:Size(min = 2, message = "검색어는 최소 2자 이상 입력해주세요.")
    @Schema(description = "검색어", example = "여름 쿠폰")
    val keyword: String? = null,

    @Schema(description = "검색어가 매핑될 필드", example = "TITLE", defaultValue = "TITLE")
    val searchType: EventSearchType = EventSearchType.TITLE,

    @ArraySchema(schema = Schema(implementation = EventStatus::class, example = "OPEN"))
    val statuses: List<EventStatus>? = null,

    @Schema(description = "이벤트 기간 필터 (현재 시각 기준)", example = "ONGOING")
    val period: EventPeriod? = null,

    @Schema(description = "생성일 시작 (ISO-8601)", example = "2026-06-01T00:00:00Z")
    val createdFrom: Instant? = null,

    @Schema(description = "생성일 종료 (ISO-8601)", example = "2026-07-31T23:59:59Z")
    val createdTo: Instant? = null,

    @Schema(description = "재고 필터: true=재고 있음, false=소진, null=전체", example = "true")
    val hasRemainingStock: Boolean? = null,
)
```

`page`, `size`, `sort`는 EventSearchCond에 두지 않고 별도 Spring `Pageable`로 받는다 (관심사 분리).

### 5.3 EventSearchType / EventPeriod

```kotlin
// EventSearchType.kt
enum class EventSearchType(val description: String) {
    TITLE("이벤트 제목"),
    // 추후 ID, DESCRIPTION 등으로 확장 가능
}

// EventPeriod.kt
enum class EventPeriod(val description: String) {
    UPCOMING("예정 (시작 전)"),
    ONGOING("진행 중 (시작됨, 종료 전)"),
    ENDED("종료됨"),
}
```

### 5.4 EventResponse

```kotlin
package com.beomjin.springeventlab.coupon.dto.response

@Schema(description = "이벤트 응답")
data class EventResponse(
    @Schema(description = "이벤트 ID (UUID v7)") val id: UUID,
    @Schema(description = "이벤트 제목") val title: String,
    @Schema(description = "총 발급 수량") val totalQuantity: Int,
    @Schema(description = "현재까지 발급된 수량") val issuedQuantity: Int,
    @Schema(description = "잔여 수량") val remainingQuantity: Int,
    @Schema(description = "이벤트 상태") val eventStatus: EventStatus,
    @Schema(description = "이벤트 시작 시각") val startedAt: Instant,
    @Schema(description = "이벤트 종료 시각") val endedAt: Instant,
    @Schema(description = "생성 시각") val createdAt: Instant?,
    @Schema(description = "마지막 수정 시각") val updatedAt: Instant?,
) {
    companion object {
        fun from(event: Event): EventResponse = EventResponse(
            id = event.id,
            title = event.title,
            totalQuantity = event.totalQuantity,
            issuedQuantity = event.issuedQuantity,
            remainingQuantity = event.remainingQuantity,
            eventStatus = event.eventStatus,
            startedAt = event.period.startedAt,   // ← Value Object 내부 접근
            endedAt = event.period.endedAt,
            createdAt = event.createdAt,
            updatedAt = event.updatedAt,
        )
    }
}
```

---

## 6. Layer Implementation

### 6.1 EventRepository (Spring Data JPA)

```kotlin
package com.beomjin.springeventlab.coupon.repository

@Repository
interface EventRepository : JpaRepository<Event, UUID>
```

> CRUD 기본은 `JpaRepository`가 제공. 복잡한 동적 검색은 별도 QueryDSL 리포지토리에서 처리.

### 6.2 EventQueryRepository (QueryDSL)

```kotlin
package com.beomjin.springeventlab.coupon.repository

@Repository
class EventQueryRepository(
    private val queryFactory: JPAQueryFactory,
) {
    fun search(cond: EventSearchCond, pageable: Pageable): Page<Event> {
        val conditions: Array<BooleanExpression> = listOfNotNull(
            EventQuery.keywordMatches(cond.keyword, cond.searchType),
            EventQuery.statusesIn(cond.statuses),
            EventQuery.periodMatches(cond.period),
            EventQuery.createdBetween(cond.createdFrom, cond.createdTo),
            EventQuery.hasRemainingStock(cond.hasRemainingStock),
        ).toTypedArray()

        val content: List<Event> = queryFactory
            .selectFrom(event)
            .where(*conditions)
            .offset(pageable.offset)
            .limit(pageable.pageSize.toLong())
            .orderBy(*EventQuery.orders(pageable.sort))
            .fetch()

        val countQuery = queryFactory
            .select(event.id.count())
            .from(event)
            .where(*conditions)

        return PageableExecutionUtils.getPage(content, pageable) { countQuery.fetchOne() ?: 0L }
    }
}
```

**학습 포인트: `listOfNotNull` + `*` spread + lazy count**
> - `listOfNotNull(...)` — Java의 `Stream.filter(Objects::nonNull)` 역할
> - `*conditions` — Kotlin에서 배열을 vararg에 전달하려면 spread 연산자 필수
> - `PageableExecutionUtils.getPage(content, pageable) { count }` — 첫 페이지에서 content가 size보다 적으면 count 쿼리를 스킵해 비용 절감

### 6.3 EventQuery (QueryDSL 표현식 모음)

```kotlin
package com.beomjin.springeventlab.coupon.service

object EventQuery {

    // ---------- Where 조건 ----------
    fun keywordMatches(keyword: String?, type: EventSearchType): BooleanExpression? {
        val kw = keyword?.takeIf { it.isNotBlank() } ?: return null
        return when (type) {
            EventSearchType.TITLE -> event.title.contains(kw)
        }
    }

    fun statusesIn(statuses: List<EventStatus>?): BooleanExpression? =
        statuses?.takeIf { it.isNotEmpty() }?.let { event.eventStatus.`in`(it) }

    fun periodMatches(period: EventPeriod?, now: Instant = Instant.now()): BooleanExpression? =
        when (period) {
            null -> null
            EventPeriod.UPCOMING -> event.period.startedAt.gt(now)
            EventPeriod.ONGOING -> event.period.startedAt.loe(now).and(event.period.endedAt.gt(now))
            EventPeriod.ENDED -> event.period.endedAt.loe(now)
        }

    fun createdBetween(from: Instant?, to: Instant?): BooleanExpression? = when {
        from != null && to != null -> event.createdAt.between(from, to)
        from != null -> event.createdAt.goe(from)
        to != null -> event.createdAt.loe(to)
        else -> null
    }

    fun hasRemainingStock(hasStock: Boolean?): BooleanExpression? = when (hasStock) {
        true -> event.issuedQuantity.lt(event.totalQuantity)
        false -> event.issuedQuantity.goe(event.totalQuantity)
        null -> null
    }

    // ---------- 정렬 ----------
    private val sortableFields: Map<String, ComparableExpressionBase<*>> = mapOf(
        "createdAt" to event.createdAt,
        "title" to event.title,
        "startedAt" to event.period.startedAt,   // Value Object 내부 경로
        "endedAt" to event.period.endedAt,
        "totalQuantity" to event.totalQuantity,
    )

    fun orders(sort: Sort): Array<OrderSpecifier<*>> =
        sort.mapNotNull { o ->
            sortableFields[o.property]?.let { if (o.isAscending) it.asc() else it.desc() }
        }.toTypedArray().ifEmpty { arrayOf(event.createdAt.desc()) }
}
```

**학습 포인트: Null-safe 필터 + 화이트리스트 정렬**
> - Where 함수들은 조건이 없으면 `null`을 반환 → QueryDSL `.where()`가 자동 무시
> - 정렬 필드는 **화이트리스트 Map**으로 관리해 `?sort=password,desc` 같은 공격을 차단 + 인덱스 없는 컬럼 정렬 방지

### 6.4 EventService

```kotlin
package com.beomjin.springeventlab.coupon.service

@Service
@Transactional(readOnly = true)
class EventService(
    private val eventRepository: EventRepository,
    private val eventQueryRepository: EventQueryRepository,
) {
    @Transactional
    fun create(request: EventCreateRequest): EventResponse {
        val event = eventRepository.save(request.toEntity())   // DateRange 불변식이 toEntity() 안에서 자동 검증
        return EventResponse.from(event)
    }

    fun getEvents(cond: EventSearchCond, pageable: Pageable): PageResponse<EventResponse> {
        val page = eventQueryRepository.search(cond, pageable)
        return PageResponse.from(page.map(EventResponse::from))
    }

    fun getEvent(id: UUID): EventResponse {
        val event = eventRepository.findByIdOrNull(id)
            ?: throw BusinessException(ErrorCode.EVENT_NOT_FOUND)
        return EventResponse.from(event)
    }
}
```

**학습 포인트: Service의 얇은 레이어**
> Service는 유스케이스 orchestration 역할만 한다. 날짜 검증(`validateDateRange`) 같은 도메인 로직은 **DateRange Value Object 내부**로 이동했다. 이것이 DDD에서 말하는 "Service 최소화, 도메인 최대화"다.

### 6.5 EventController

```kotlin
package com.beomjin.springeventlab.coupon.controller

@RestController
@RequestMapping("/api/v1/events")
@Tag(name = "Event", description = "이벤트 관리 API")
class EventController(
    private val eventService: EventService,
) {
    @PostMapping
    @Operation(summary = "이벤트 생성", description = "새로운 선착순 이벤트를 생성합니다")
    fun create(
        @Valid @RequestBody request: EventCreateRequest,
    ): ResponseEntity<EventResponse> =
        ResponseEntity.status(HttpStatus.CREATED).body(eventService.create(request))

    @GetMapping
    @Operation(summary = "이벤트 목록 조회", description = "검색 조건에 따라 이벤트 목록을 페이징하여 조회합니다")
    fun getEvents(
        @ParameterObject @Valid cond: EventSearchCond,
        @ParameterObject
        @PageableDefault(size = 20, sort = ["createdAt"], direction = Sort.Direction.DESC)
        pageable: Pageable,
    ): ResponseEntity<PageResponse<EventResponse>> =
        ResponseEntity.ok(eventService.getEvents(cond, pageable))

    @GetMapping("/{id}")
    @Operation(summary = "이벤트 상세 조회", description = "이벤트 ID로 상세 정보를 조회합니다")
    fun getEvent(@PathVariable id: UUID): ResponseEntity<EventResponse> =
        ResponseEntity.ok(eventService.getEvent(id))
}
```

**학습 포인트: `@ParameterObject`**
> springdoc의 `@ParameterObject`를 붙이면 EventSearchCond의 모든 필드가 Swagger UI에 **개별 query parameter**로 풀려서 표시된다. `@ModelAttribute`만 쓰면 single JSON body로 그려질 수 있다. 추가로 `Pageable`에도 `@ParameterObject`를 붙여 `page`/`size`/`sort`가 자연스럽게 노출된다.

**1-based Pagination 활성화**
> `application.yaml`:
> ```yaml
> spring:
>   data:
>     web:
>       pageable:
>         one-indexed-parameters: true   # ← 클라이언트 page=1 → 내부 PageRequest(0)
>         default-page-size: 20
>         max-page-size: 100
> ```

---

## 7. Error Handling

### 7.1 ErrorCode 컨벤션

**패턴**: `{DOMAIN}_{CONDITION}` — 도메인 prefix 먼저, 공통 에러는 prefix 없음

| Code | HTTP | 메시지 | 발생 지점 |
|------|------|--------|---------|
| `INVALID_INPUT` | `C400` / 400 | 잘못된 입력 | Bean Validation / GlobalExceptionHandler |
| `INVALID_DATE_RANGE` | `C400-1` / 400 | 잘못된 기간 (시작 >= 종료) | `DateRange` init |
| `EVENT_NOT_FOUND` | `E404` / 404 | 이벤트를 찾을 수 없음 | `EventService.getEvent` |
| `EVENT_NOT_OPEN` | `E409-1` / 409 | 진행 중인 이벤트가 아님 | `Event.issue` (status 검증) |
| `EVENT_OUT_OF_STOCK` | `E409-2` / 409 | 재고 소진 | `Event.issue` (재고 검증) |
| `EVENT_INVALID_STATUS_TRANSITION` | `E409-3` / 409 | 허용되지 않은 상태 전환 | `EventStatus.transitionTo` |

**서브코드 전략**: 같은 HTTP 상태에서 원인 구분이 필요하면 `E409-1`, `E409-2`로 확장. 클라이언트는 원인별 분기 UX 가능 (재고 소진 → "다른 이벤트 추천", 상태 불일치 → "잠시 후 시도").

### 7.2 Error Scenarios

| Scenario | ErrorCode | HTTP | Trigger |
|----------|-----------|------|---------|
| 존재하지 않는 이벤트 조회 | `EVENT_NOT_FOUND` | 404 | `GET /api/v1/events/{id}` |
| 시작일 >= 종료일 | `INVALID_DATE_RANGE` | 400 | `POST` 시 `DateRange` 생성자 |
| Bean Validation 실패 (title 빈값 등) | `INVALID_INPUT` | 400 | GlobalExceptionHandler |
| JSON 파싱 실패 (잘못된 date/enum) | `INVALID_INPUT` | 400 | GlobalExceptionHandler |
| 잘못된 status/period/searchType enum | `INVALID_INPUT` | 400 | Spring 바인딩 실패 |
| 잘못된 정렬 필드 (화이트리스트 밖) | — (조용히 무시 + 기본 정렬 적용) | — | `EventQuery.orders` |

---

## 8. Package Structure

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
│   │   ├── Event.kt                 # @Embedded DateRange + Rich Domain
│   │   ├── EventStatus.kt           # isIssuable + 전이 규칙
│   │   └── CouponIssue.kt           # eventId UUID (No @ManyToOne)
│   ├── repository/
│   │   ├── EventRepository.kt       # JpaRepository
│   │   └── EventQueryRepository.kt  # QueryDSL + PageableExecutionUtils
│   └── service/
│       ├── EventQuery.kt            # QueryDSL 표현식 모음
│       └── EventService.kt
└── global/
    ├── common/
    │   ├── BaseTimeEntity.kt
    │   ├── BaseCreatedTimeEntity.kt
    │   ├── DateRange.kt             # NEW @Embeddable
    │   └── PageResponse.kt
    ├── config/
    │   ├── JpaConfig.kt             # JPAQueryFactory bean
    │   └── SwaggerConfig.kt
    └── exception/
        ├── ErrorCode.kt             # EVENT_* + INVALID_DATE_RANGE
        ├── ErrorResponse.kt
        ├── BusinessException.kt
        └── GlobalExceptionHandler.kt

src/main/resources/
├── application.yaml                 # spring.data.web.pageable.one-indexed-parameters: true
└── db/migration/
    ├── V20260409174330__create_event_table.sql
    └── V20260409174359__create_coupon_issue_table.sql
```

---

## 9. Implementation Order

| Step | Task | Files | Status |
|------|------|-------|:------:|
| 1 | uuid-creator 의존성 | `build.gradle.kts` | ✅ |
| 2 | `spring-boot-starter-flyway` 추가 (Spring Boot 4 필수) | `build.gradle.kts` | ✅ |
| 3 | Flyway 마이그레이션 | `V20260409174330__`, `V20260409174359__` | ✅ |
| 4 | DateRange Value Object | `global/common/DateRange.kt` | ✅ |
| 5 | Entity + Enum 리팩토링 | `Event.kt`, `EventStatus.kt`, `CouponIssue.kt` | ✅ |
| 6 | ErrorCode 재구성 (`{DOMAIN}_{CONDITION}`) | `ErrorCode.kt` | ✅ |
| 7 | EventRepository (JpaRepository) | `repository/EventRepository.kt` | ✅ |
| 8 | EventSearchCond + EventSearchType + EventPeriod | `dto/request/` | ✅ |
| 9 | EventQuery (QueryDSL 표현식) | `service/EventQuery.kt` | ✅ |
| 10 | EventQueryRepository (QueryDSL) | `repository/EventQueryRepository.kt` | ✅ |
| 11 | DTO | `EventCreateRequest.kt`, `EventResponse.kt` | ✅ |
| 12 | EventService | `service/EventService.kt` | ✅ |
| 13 | EventController + `@PageableDefault` + `@ParameterObject` | `controller/EventController.kt` | ✅ |
| 14 | `application.yaml` — `one-indexed-parameters: true` | `application.yaml` | ✅ |
| 15 | 수동 E2E (Docker Compose + Swagger UI) | — | Pending |
| 16 | 통합 테스트 (Testcontainers + MockMvc) | `EventIntegrationTest.kt` | Pending |

---

## 10. Test Plan

### 10.1 Test Scope

| Type | Target | Tool |
|------|--------|------|
| Unit Test | DateRange 불변식, EventStatus 전이, Event.issue() | JUnit5 |
| Integration Test | API 전체 흐름 | Testcontainers + MockMvc |
| Manual | Swagger UI 검증 | `/swagger-ui.html` |

### 10.2 Key Test Scenarios

- [ ] **Happy path**: 이벤트 생성 → 검색 → 상세 조회
- [ ] **DateRange 불변식**: `startedAt >= endedAt` → `INVALID_DATE_RANGE` 400
- [ ] **EventStatus 전이**: READY→OPEN→CLOSED 성공, 역전이/스킵 → `EVENT_INVALID_STATUS_TRANSITION` 409
- [ ] **Event.issue()**: status가 OPEN 아님 → `EVENT_NOT_OPEN`, 재고 소진 → `EVENT_OUT_OF_STOCK`
- [ ] **검색 필터**: keyword, statuses, period, createdFrom/To, hasRemainingStock 각각/조합
- [ ] **Pagination**: 1-based 동작, `sort` 다중 정렬, 화이트리스트 밖 sort 필드 무시
- [ ] **Not Found**: 존재하지 않는 UUID 조회 → `EVENT_NOT_FOUND` 404
- [ ] **Bean Validation**: title 빈값, totalQuantity 0, size > 100 → `INVALID_INPUT` 400

---

## 11. Security Considerations

- [x] Bean Validation으로 입력값 검증 (XSS, SQL Injection — JPA parameterized query)
- [x] 정렬 필드 **화이트리스트** (`sortableFields`) — 임의 컬럼 정렬 방지
- [x] `hasRemainingStock` 등 boolean 타입 필드로 전달 (SQL injection 원천 차단)
- [x] Entity 필드 `protected set` — 외부에서 직접 수정 불가
- [x] DTO 분리 — Entity 내부 구조가 API 응답에 노출되지 않음
- [x] DateRange 불변식 — 잘못된 상태의 Event가 메모리에 존재 불가
- [ ] 인증/인가: Out of Scope (현재 단계)

---

## 12. Known Issues & Decisions Log

| 이슈 | 결정 | 근거 |
|-----|------|------|
| `startedAt`/`endedAt` 묶기 | `@Embeddable DateRange` 추출 | 두 번째 기간 개념(Coupon 유효기간 등) 예정 + Value Object 정석 |
| Event ↔ CouponIssue 연관관계 | **사용 안 함** (eventId: UUID) | DDD Aggregate 경계 + 선착순 성능 + 분리 가능성 |
| `validateDateRange` 위치 | DateRange `init` 블록 | Anemic Domain Model 회피, 규칙이 데이터와 함께 있어야 함 |
| 커스텀 Pageable vs Spring Pageable | Spring `Pageable` + `@PageableDefault` | 과공학 회피, `one-indexed-parameters`로 1-based 자동 처리 |
| Flyway 미실행 | `flyway-core` 단독 → `spring-boot-starter-flyway` 추가 | Spring Boot 4에서 auto-config 모듈 분리 |
| 에러 코드 네이밍 | `{DOMAIN}_{CONDITION}` 패턴 통일 (`EVENT_INVALID_DATE` → 삭제, `INVALID_DATE_RANGE`는 공통으로) | 그룹핑/IDE 자동완성/도메인별 필터링 용이 |
| CONFLICT 세분화 | `E409-1/-2/-3` 서브코드 | 클라이언트가 원인별 분기 가능 (재고 vs 상태 vs 전환) |

---

## Version History

| Version | Date | Changes | Author |
|---------|------|---------|--------|
| 0.1 | 2026-04-09 | Initial draft | beomjin |
| 0.2 | 2026-04-09 | UUID v7 ID 전략 적용 | beomjin |
| 0.3 | 2026-04-10 | 엔티티 구현 동기화 (JdbcTypeCode, Instant, 패키지) | beomjin |
| **0.4** | **2026-04-10** | **DDD 리팩토링 + 검색 확장**: DateRange `@Embeddable` Value Object 추출, EventStatus에 `isIssuable` 추가 및 전이 규칙 캡슐화, Aggregate 경계 명시 (Event ↔ CouponIssue `@ManyToOne` 미사용), ErrorCode `{DOMAIN}_{CONDITION}` 패턴 통일 + 원인별 서브코드(`E409-*`), `INVALID_DATE_RANGE` 공통 에러로 이동, EventSearchCond 다중 필터 (keyword/searchType/statuses/period/createdFrom/To/hasRemainingStock), EventSearchType/EventPeriod enum 분리, EventQuery object + EventQueryRepository (QueryDSL + `PageableExecutionUtils`), Spring 네이티브 Pageable(`@PageableDefault` + `@ParameterObject` + `one-indexed-parameters: true`), Spring Boot 4 Flyway `spring-boot-starter-flyway` 필수, `CLAUDE.md` DDD 작업 원칙 문서화 | beomjin |
