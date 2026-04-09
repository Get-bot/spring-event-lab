# Event CRUD Design Document

> **Summary**: 선착순 이벤트 도메인 엔티티 설계 및 관리자용 CRUD API의 상세 기술 명세
>
> **Project**: spring-event-lab
> **Version**: 0.0.1-SNAPSHOT
> **Author**: beomjin
> **Date**: 2026-04-09
> **Status**: Draft
> **Planning Doc**: [01-event-crud.plan.md](../../01-plan/features/01-event-crud.plan.md)

---

## Executive Summary

| Perspective | Content |
|-------------|---------|
| **Problem** | 선착순 발급 시스템의 기반이 되는 이벤트 도메인 모델과 관리 API가 없어 후속 기능(Redis, Kafka) 개발이 불가능 |
| **Solution** | JPA Entity + Flyway 마이그레이션으로 도메인 모델을 구축하고, Layered Architecture 기반 REST API로 CRUD 제공 |
| **Function/UX Effect** | 관리자가 이벤트를 생성·조회하고, 상태(READY/OPEN/CLOSED)를 관리하며, 잔여 수량을 실시간 확인 가능 |
| **Core Value** | 프로젝트 전체 아키텍처 패턴(Entity Convention, DTO 패턴, ErrorCode 체계)의 표준을 확립하고, 후속 feature의 토대 마련 |

---

## 1. Overview

### 1.1 Design Goals

- Plan 문서의 Entity Convention(생성자 파라미터 + `protected set`)을 정확히 구현
- **UUID v7 ID 전략** 적용 — `uuid-creator` 라이브러리 `UuidCreator.getTimeOrderedEpoch()` + `Persistable<UUID>`
- 기존 `global` 패키지(`BaseTimeEntity`, `ErrorCode`, `PageResponse`)를 최대한 활용
- Flyway 마이그레이션으로 스키마를 코드로 관리하여 재현 가능한 환경 보장
- Swagger UI를 통한 즉시 테스트 가능한 API 문서 제공

### 1.2 Design Principles

- **Layered Architecture**: Controller → Service → Repository 단방향 의존
- **DTO 분리**: Entity를 API 응답에 직접 노출하지 않음 (보안 + 유연성)
- **도메인 로직 캡슐화**: Entity 내부에 상태 변경 메서드를 두어 비즈니스 규칙 보호
- **Fail-fast Validation**: Bean Validation + 도메인 전용 ErrorCode로 빠른 에러 응답

---

## 2. Architecture

### 2.1 Component Diagram

```
┌──────────────┐     ┌──────────────────────────────────────────┐     ┌──────────────┐
│              │     │            Spring Boot App               │     │              │
│   Client     │────▶│  Controller → Service → Repository      │────▶│  PostgreSQL  │
│  (Swagger)   │     │      ↑            ↑           ↑         │     │  (eventlab)  │
│              │◀────│  DTO/Validation  Entity    JPA/QueryDSL  │◀────│              │
└──────────────┘     └──────────────────────────────────────────┘     └──────────────┘
                                      ↑
                              GlobalExceptionHandler
                              (ErrorCode → ErrorResponse)
```

### 2.2 Data Flow

```
[POST 이벤트 생성]
Client Request → Bean Validation → EventController.create()
  → EventService.create() → Event 엔티티 생성 → EventRepository.save()
  → EventResponse DTO 변환 → 201 Created 응답

[GET 목록 조회]
Client Request → EventController.getEvents(page, size, status)
  → EventService.getEvents() → EventRepository (QueryDSL 동적 쿼리)
  → Page<Event> → PageResponse<EventResponse> 변환 → 200 OK 응답

[GET 상세 조회]
Client Request → EventController.getEvent(id)
  → EventService.getEvent() → EventRepository.findById()
  → Event → EventResponse 변환 → 200 OK 응답
  → 없으면 BusinessException(EVENT_NOT_FOUND) → 404 응답
```

### 2.3 Dependencies (기존 global 패키지 활용)

| 신규 컴포넌트 | 의존하는 기존 컴포넌트 | 용도 |
|---------------|----------------------|------|
| `Event` entity | `global.common.BaseTimeEntity` | createdAt, updatedAt 자동 관리 |
| `CouponIssue` entity | `global.common.BaseCreatedTimeEntity` | createdAt만 필요 (수정 불가) |
| `EventService` | `global.exception.BusinessException` | 도메인 예외 발생 |
| `EventService` | `global.exception.ErrorCode` | 에러 코드 참조 (+ 도메인 전용 추가) |
| `EventController` | `global.common.PageResponse` | 페이징 응답 래핑 |
| (자동 처리) | `global.exception.GlobalExceptionHandler` | Validation, BusinessException 처리 |
| (자동 처리) | `global.config.JpaConfig` | JPA Auditing, QueryDSL |
| (자동 처리) | `global.config.SwaggerConfig` | API 문서 자동 생성 |
| (외부 라이브러리) | `com.github.f4b6a3:uuid-creator` | UUID v7 생성 (`build.gradle.kts`에 추가 필요) |

---

## 3. Data Model

### 3.1 Entity 설계

#### Event Entity

> Convention: 생성자 파라미터로 필드를 받고, body에서 `var ... = param` + `protected set`
> ID: UUID v7 — `UuidCreator.getTimeOrderedEpoch()` + `Persistable<UUID>` 구현
> `BaseTimeEntity()` 상속 → createdAt, updatedAt 자동 관리

```kotlin
package com.beomjin.springeventlab.domain.event.entity

import com.github.f4b6a3.uuid.UuidCreator
import org.springframework.data.domain.Persistable
import java.util.UUID

@Entity
@Table(name = "event")
class Event(
    title: String,
    totalQuantity: Int,
    eventStatus: EventStatus,
    startedAt: LocalDateTime,
    endedAt: LocalDateTime,
) : BaseTimeEntity(), Persistable<UUID> {

    @Id
    @Column(columnDefinition = "UUID")
    private val id: UUID = UuidCreator.getTimeOrderedEpoch()

    override fun getId(): UUID = id

    @Transient
    private var _isNew: Boolean = true

    override fun isNew(): Boolean = _isNew

    @PostPersist
    @PostLoad
    fun markNotNew() {
        _isNew = false
    }

    @Column(nullable = false, length = 200, columnDefinition = "VARCHAR(200) COMMENT '이벤트 제목'")
    var title: String = title
        protected set

    @Column(nullable = false, columnDefinition = "INT COMMENT '총 수량'")
    var totalQuantity: Int = totalQuantity
        protected set

    @Column(nullable = false, columnDefinition = "INT COMMENT '발급된 수량'")
    var issuedQuantity: Int = 0
        protected set

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20, columnDefinition = "VARCHAR(20) COMMENT '이벤트 상태'")
    var eventStatus: EventStatus = eventStatus
        protected set

    @Column(nullable = false, columnDefinition = "TIMESTAMP COMMENT '시작 시각'")
    var startedAt: LocalDateTime = startedAt
        protected set

    @Column(nullable = false, columnDefinition = "TIMESTAMP COMMENT '종료 시각'")
    var endedAt: LocalDateTime = endedAt
        protected set

    // --- 도메인 로직 ---

    /** 잔여 수량 계산 */
    val remainingQuantity: Int
        get() = totalQuantity - issuedQuantity

    /** 발급 가능 여부 확인 */
    fun isIssuable(): Boolean =
        eventStatus == EventStatus.OPEN && remainingQuantity > 0

    /** 쿠폰 1장 발급 처리 (재고 차감) — redis-stock에서 사용 예정 */
    fun issue() {
        check(isIssuable()) { "발급 불가능한 상태입니다. status=$eventStatus, remaining=$remainingQuantity" }
        issuedQuantity++
    }

    /** 이벤트 오픈 */
    fun open() {
        check(eventStatus == EventStatus.READY) { "READY 상태에서만 오픈 가능합니다. current=$eventStatus" }
        eventStatus = EventStatus.OPEN
    }

    /** 이벤트 종료 */
    fun close() {
        check(eventStatus == EventStatus.OPEN) { "OPEN 상태에서만 종료 가능합니다. current=$eventStatus" }
        eventStatus = EventStatus.CLOSED
    }
}
```

**학습 포인트: UUID v7을 쓰는 이유**
> `Long` 자동 증가(BIGSERIAL) 대비 UUID v7의 장점:
> 1. **보안**: API에서 `/events/1`, `/events/2` 처럼 ID를 추측할 수 없음 (IDOR 공격 방지)
> 2. **분산 환경**: 여러 서버/DB에서 동시에 ID를 생성해도 충돌 없음
> 3. **정렬 가능**: UUID v7은 앞 48비트가 타임스탬프라서 생성 순서대로 정렬됨 (B-tree 인덱스 성능 우수)
> 4. **uuid-creator**: `UuidCreator.getTimeOrderedEpoch()`로 RFC 9562 UUID v7 생성
>
> `Persistable<UUID>`를 구현하는 이유:
> Spring Data JPA의 `save()`는 내부적으로 `isNew()`를 호출하여 `persist`(INSERT) vs `merge`(UPDATE)를 결정한다.
> ID가 `Long`이면 `id == 0L`으로 new 판단이 가능하지만, UUID는 항상 non-null이므로 JPA가 항상 `merge`를 시도한다.
> `Persistable<UUID>`를 구현하여 `@Transient _isNew` 플래그로 직접 new 여부를 제어한다.
> `@PostPersist`(저장 후), `@PostLoad`(조회 후)에서 `_isNew = false`로 설정한다.

**학습 포인트: 왜 Entity에 도메인 로직을 두는가?**
> Service에서 `event.eventStatus = EventStatus.OPEN` 처럼 직접 필드를 바꾸면, 상태 전이 규칙(READY→OPEN만 가능)을 매번 Service에서 검증해야 한다. Entity 내부에 `open()`, `close()` 메서드를 두면 **비즈니스 규칙이 한 곳에 집중**되어 유지보수가 쉬워진다. 이것이 **Rich Domain Model** 패턴이다.

#### EventStatus Enum

```kotlin
package com.beomjin.springeventlab.domain.event.entity

enum class EventStatus(val description: String) {
    READY("이벤트 준비 중 (시작 전)"),
    OPEN("이벤트 진행 중 (발급 가능)"),
    CLOSED("이벤트 종료"),
    ;

    // 상태 전이 다이어그램:
    // READY → OPEN → CLOSED
    // (역방향 전이 불가)
}
```

#### CouponIssue Entity (스키마만, 로직은 redis-stock에서)

```kotlin
package com.beomjin.springeventlab.domain.event.entity

import com.github.f4b6a3.uuid.UuidCreator
import org.springframework.data.domain.Persistable
import java.util.UUID

@Entity
@Table(
    name = "coupon_issue",
    uniqueConstraints = [UniqueConstraint(name = "uk_coupon_issue", columnNames = ["event_id", "user_id"])]
)
class CouponIssue(
    eventId: UUID,
    userId: Long,
) : BaseCreatedTimeEntity(), Persistable<UUID> {

    @Id
    @Column(columnDefinition = "UUID")
    private val id: UUID = UuidCreator.getTimeOrderedEpoch()

    override fun getId(): UUID = id

    @Transient
    private var _isNew: Boolean = true

    override fun isNew(): Boolean = _isNew

    @PostPersist
    @PostLoad
    fun markNotNew() {
        _isNew = false
    }

    @Column(nullable = false, columnDefinition = "UUID COMMENT '이벤트 ID'")
    var eventId: UUID = eventId
        protected set

    @Column(nullable = false, columnDefinition = "BIGINT COMMENT '사용자 ID'")
    var userId: Long = userId
        protected set
}
```

### 3.2 Entity Relationships

```
[Event] 1 ──── N [CouponIssue]
  │                    │
  ├─ id: UUID (PK)     ├─ id: UUID (PK)
  ├─ title             ├─ eventId: UUID (FK → event.id)
  ├─ totalQuantity     ├─ userId: Long
  ├─ issuedQuantity    └─ createdAt
  ├─ eventStatus
  ├─ startedAt
  ├─ endedAt
  ├─ createdAt
  └─ updatedAt

  * PK: UUID v7 (UuidCreator.getTimeOrderedEpoch())
  * FK 관계는 DDL에서 REFERENCES로 정의
  * JPA 연관관계 매핑은 redis-stock에서 필요 시 추가
  * 현재는 eventId를 UUID 필드로 보관 (불필요한 JOIN 방지)
  * userId는 Long 유지 (User 엔티티 미구현, 향후 변경 가능)
```

### 3.3 Flyway Migration

> `src/main/resources/db/migration/` 경로에 작성
> JPA `ddl-auto: validate` — Flyway가 DDL 관리, JPA는 검증만 수행

#### V1__create_event_table.sql

```sql
CREATE TABLE event (
    id              UUID            PRIMARY KEY,
    title           VARCHAR(200)    NOT NULL,
    total_quantity  INT             NOT NULL,
    issued_quantity INT             NOT NULL DEFAULT 0,
    event_status    VARCHAR(20)     NOT NULL DEFAULT 'READY',
    started_at      TIMESTAMP       NOT NULL,
    ended_at        TIMESTAMP       NOT NULL,
    created_at      TIMESTAMP       NOT NULL DEFAULT NOW(),
    updated_at      TIMESTAMP       NOT NULL DEFAULT NOW()
);

COMMENT ON TABLE event IS '선착순 이벤트';
COMMENT ON COLUMN event.title IS '이벤트 제목';
COMMENT ON COLUMN event.total_quantity IS '총 수량';
COMMENT ON COLUMN event.issued_quantity IS '발급된 수량';
COMMENT ON COLUMN event.event_status IS '이벤트 상태 (READY/OPEN/CLOSED)';
COMMENT ON COLUMN event.started_at IS '시작 시각';
COMMENT ON COLUMN event.ended_at IS '종료 시각';
```

> **PostgreSQL UUID 타입**: 16바이트 고정 크기로 저장. UUID v7은 앞부분이 타임스탬프이므로 B-tree 인덱스에서 순차 삽입 패턴 → UUID v4 대비 인덱스 성능 우수. `DEFAULT`절 없음 — 애플리케이션(UuidCreator)에서 생성.

#### V2__create_coupon_issue_table.sql

```sql
CREATE TABLE coupon_issue (
    id          UUID            PRIMARY KEY,
    event_id    UUID            NOT NULL REFERENCES event(id),
    user_id     BIGINT          NOT NULL,
    created_at  TIMESTAMP       NOT NULL DEFAULT NOW(),
    CONSTRAINT uk_coupon_issue UNIQUE (event_id, user_id)
);

CREATE INDEX idx_coupon_issue_event_id ON coupon_issue(event_id);
CREATE INDEX idx_coupon_issue_user_id ON coupon_issue(user_id);

COMMENT ON TABLE coupon_issue IS '쿠폰 발급 이력';
COMMENT ON COLUMN coupon_issue.event_id IS '이벤트 ID';
COMMENT ON COLUMN coupon_issue.user_id IS '사용자 ID';
```

---

## 4. API Specification

### 4.1 Endpoint List

| Method | Path | Description | Request Body | Response |
|--------|------|-------------|-------------|----------|
| POST | `/api/v1/events` | 이벤트 생성 | `EventCreateRequest` | `EventResponse` (201) |
| GET | `/api/v1/events` | 이벤트 목록 조회 | Query: page, size, status | `PageResponse<EventResponse>` (200) |
| GET | `/api/v1/events/{id}` | 이벤트 상세 조회 | Path: id | `EventResponse` (200) |

### 4.2 Detailed Specification

#### POST `/api/v1/events` — 이벤트 생성

**Request:**
```json
{
  "title": "2026년 여름 쿠폰 이벤트",
  "totalQuantity": 1000,
  "startedAt": "2026-05-01T00:00:00",
  "endedAt": "2026-05-31T23:59:59"
}
```

**Validation Rules:**
| Field | Rule | Error Message |
|-------|------|---------------|
| title | `@NotBlank`, `@Size(max=200)` | "이벤트 제목은 필수입니다" / "200자 이하여야 합니다" |
| totalQuantity | `@NotNull`, `@Min(1)` | "총 수량은 1 이상이어야 합니다" |
| startedAt | `@NotNull` | "시작 시각은 필수입니다" |
| endedAt | `@NotNull` | "종료 시각은 필수입니다" |
| (cross-field) | startedAt < endedAt | Service에서 검증, `INVALID_INPUT` 에러 |

**Response (201 Created):**
```json
{
  "id": "019644a2-3b00-7f8a-a1e2-4c5d6e7f8a9b",
  "title": "2026년 여름 쿠폰 이벤트",
  "totalQuantity": 1000,
  "issuedQuantity": 0,
  "remainingQuantity": 1000,
  "eventStatus": "READY",
  "startedAt": "2026-05-01T00:00:00",
  "endedAt": "2026-05-31T23:59:59",
  "createdAt": "2026-04-09T10:00:00",
  "updatedAt": "2026-04-09T10:00:00"
}
```

#### GET `/api/v1/events` — 이벤트 목록 조회

**Query Parameters:**
| Param | Type | Default | Description |
|-------|------|---------|-------------|
| page | int | 0 | 페이지 번호 (0-based) |
| size | int | 20 | 페이지 크기 |
| status | EventStatus? | null | 상태 필터 (선택) |

**Response (200 OK):**
```json
{
  "content": [
    {
      "id": "019644a2-3b00-7f8a-a1e2-4c5d6e7f8a9b",
      "title": "2026년 여름 쿠폰 이벤트",
      "totalQuantity": 1000,
      "issuedQuantity": 150,
      "remainingQuantity": 850,
      "eventStatus": "OPEN",
      "startedAt": "2026-05-01T00:00:00",
      "endedAt": "2026-05-31T23:59:59",
      "createdAt": "2026-04-09T10:00:00",
      "updatedAt": "2026-04-09T12:00:00"
    }
  ],
  "page": 0,
  "size": 20,
  "totalElements": 1,
  "totalPages": 1
}
```

#### GET `/api/v1/events/{id}` — 이벤트 상세 조회

**Path Parameter:** `id` (UUID) — 이벤트 ID

**Response (200 OK):** `EventResponse` (위와 동일 구조)

**Error (404):**
```json
{
  "code": "E404",
  "message": "이벤트를 찾을 수 없습니다.",
  "errors": {},
  "timestamp": "2026-04-09T10:00:00"
}
```

---

## 5. DTO Specification

### 5.1 EventCreateRequest

```kotlin
package com.beomjin.springeventlab.domain.event.dto

data class EventCreateRequest(
    @field:NotBlank(message = "이벤트 제목은 필수입니다")
    @field:Size(max = 200, message = "이벤트 제목은 200자 이하여야 합니다")
    val title: String,

    @field:NotNull(message = "총 수량은 필수입니다")
    @field:Min(value = 1, message = "총 수량은 1 이상이어야 합니다")
    val totalQuantity: Int,

    @field:NotNull(message = "시작 시각은 필수입니다")
    val startedAt: LocalDateTime,

    @field:NotNull(message = "종료 시각은 필수입니다")
    val endedAt: LocalDateTime,
) {
    fun toEntity(): Event = Event(
        title = title,
        totalQuantity = totalQuantity,
        eventStatus = EventStatus.READY,
        startedAt = startedAt,
        endedAt = endedAt,
    )
}
```

**학습 포인트: `toEntity()`를 DTO에 두는 이유**
> DTO → Entity 변환 로직을 Service에 두면 Service가 비대해진다. DTO는 "요청 데이터의 표현"이므로, 자신이 어떤 Entity로 변환되는지 아는 것이 자연스럽다. 반대로 Entity → DTO 변환은 `EventResponse.from(event)` 팩토리 메서드로 구현한다.

### 5.2 EventResponse

```kotlin
package com.beomjin.springeventlab.domain.event.dto

data class EventResponse(
    val id: UUID,
    val title: String,
    val totalQuantity: Int,
    val issuedQuantity: Int,
    val remainingQuantity: Int,
    val eventStatus: EventStatus,
    val startedAt: LocalDateTime,
    val endedAt: LocalDateTime,
    val createdAt: LocalDateTime,
    val updatedAt: LocalDateTime,
) {
    companion object {
        fun from(event: Event): EventResponse = EventResponse(
            id = event.id,
            title = event.title,
            totalQuantity = event.totalQuantity,
            issuedQuantity = event.issuedQuantity,
            remainingQuantity = event.remainingQuantity,
            eventStatus = event.eventStatus,
            startedAt = event.startedAt,
            endedAt = event.endedAt,
            createdAt = event.createdAt,
            updatedAt = event.updatedAt,
        )
    }
}
```

---

## 6. Layer Implementation

### 6.1 Repository

```kotlin
package com.beomjin.springeventlab.domain.event.repository

interface EventRepository : JpaRepository<Event, UUID> {
    fun findByEventStatus(eventStatus: EventStatus, pageable: Pageable): Page<Event>
}
```

```kotlin
interface CouponIssueRepository : JpaRepository<CouponIssue, UUID>
```

> **참고**: 목록 조회 시 status 필터가 optional이므로, status가 null이면 `findAll(pageable)`, 있으면 `findByEventStatus(status, pageable)`를 호출한다. 복잡한 동적 쿼리가 필요해지면 QueryDSL Custom Repository로 확장한다.

### 6.2 Service

```kotlin
package com.beomjin.springeventlab.domain.event.service

@Service
@Transactional(readOnly = true)
class EventService(
    private val eventRepository: EventRepository,
) {

    @Transactional
    fun create(request: EventCreateRequest): EventResponse {
        validateDateRange(request.startedAt, request.endedAt)
        val event = eventRepository.save(request.toEntity())
        return EventResponse.from(event)
    }

    fun getEvents(
        page: Int,
        size: Int,
        status: EventStatus?,
    ): PageResponse<EventResponse> {
        val pageable = PageRequest.of(page, size, Sort.by(Sort.Direction.DESC, "createdAt"))
        val events = if (status != null) {
            eventRepository.findByEventStatus(status, pageable)
        } else {
            eventRepository.findAll(pageable)
        }
        return PageResponse.from(events.map { EventResponse.from(it) })
    }

    fun getEvent(id: UUID): EventResponse {
        val event = eventRepository.findByIdOrNull(id)
            ?: throw BusinessException(ErrorCode.EVENT_NOT_FOUND)
        return EventResponse.from(event)
    }

    private fun validateDateRange(startedAt: LocalDateTime, endedAt: LocalDateTime) {
        if (startedAt >= endedAt) {
            throw BusinessException(ErrorCode.INVALID_EVENT_DATE)
        }
    }
}
```

**학습 포인트: `@Transactional(readOnly = true)` 클래스 레벨**
> 클래스 레벨에 `readOnly = true`를 두고, 쓰기 메서드에만 `@Transactional`을 오버라이드한다. 읽기 전용 트랜잭션은 JPA Dirty Checking을 건너뛰어 성능이 좋다.

### 6.3 Controller

```kotlin
package com.beomjin.springeventlab.domain.event.controller

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
    ): ResponseEntity<EventResponse> {
        val response = eventService.create(request)
        return ResponseEntity.status(HttpStatus.CREATED).body(response)
    }

    @GetMapping
    @Operation(summary = "이벤트 목록 조회", description = "이벤트 목록을 페이징하여 조회합니다")
    fun getEvents(
        @RequestParam(defaultValue = "0") page: Int,
        @RequestParam(defaultValue = "20") size: Int,
        @RequestParam(required = false) status: EventStatus?,
    ): ResponseEntity<PageResponse<EventResponse>> {
        val response = eventService.getEvents(page, size, status)
        return ResponseEntity.ok(response)
    }

    @GetMapping("/{id}")
    @Operation(summary = "이벤트 상세 조회", description = "이벤트 ID로 상세 정보를 조회합니다")
    fun getEvent(
        @PathVariable id: UUID,
    ): ResponseEntity<EventResponse> {
        val response = eventService.getEvent(id)
        return ResponseEntity.ok(response)
    }
}
```

---

## 7. Error Handling

### 7.1 도메인 전용 ErrorCode 추가

기존 `global.exception.ErrorCode`에 이벤트 도메인 전용 코드를 추가한다:

```kotlin
// ErrorCode.kt에 추가할 항목
EVENT_NOT_FOUND(HttpStatus.NOT_FOUND, "E404", "이벤트를 찾을 수 없습니다."),
INVALID_EVENT_DATE(HttpStatus.BAD_REQUEST, "E400", "종료 시각은 시작 시각 이후여야 합니다."),
```

**코드 네이밍 규칙:**
- `C` prefix: 공통 에러 (Common) — 기존 코드
- `E` prefix: 이벤트 도메인 (Event) — 신규 추가
- 이후 도메인별: `R` (Redis), `K` (Kafka) 등

### 7.2 Error Scenarios

| Scenario | ErrorCode | HTTP Status | Trigger |
|----------|-----------|-------------|---------|
| 존재하지 않는 이벤트 조회 | `EVENT_NOT_FOUND` | 404 | `GET /api/v1/events/{id}` |
| 시작일 ≥ 종료일 | `INVALID_EVENT_DATE` | 400 | `POST /api/v1/events` |
| Bean Validation 실패 | `INVALID_INPUT` (기존) | 400 | title 빈값, 수량 0 이하 등 |
| JSON 파싱 실패 | `INVALID_INPUT` (기존) | 400 | 잘못된 날짜 형식, enum 값 등 |
| 잘못된 EventStatus enum | `INVALID_INPUT` (기존) | 400 | `?status=INVALID` |

> **GlobalExceptionHandler가 자동 처리하는 항목**: Bean Validation(`MethodArgumentNotValidException`), JSON 파싱(`HttpMessageNotReadableException`), enum 변환 실패(`InvalidFormatException`)

---

## 8. Package Structure

```
src/main/kotlin/com/beomjin/springeventlab/
├── global/                          # (기존) 공통 인프라
│   ├── common/
│   │   ├── BaseTimeEntity.kt        # createdAt + updatedAt
│   │   ├── BaseCreatedTimeEntity.kt # createdAt만
│   │   └── PageResponse.kt          # 페이징 응답 DTO
│   ├── config/
│   │   ├── JpaConfig.kt             # JPA Auditing + QueryDSL
│   │   └── SwaggerConfig.kt         # OpenAPI 설정
│   └── exception/
│       ├── ErrorCode.kt             # ← EVENT_NOT_FOUND, INVALID_EVENT_DATE 추가
│       ├── ErrorResponse.kt
│       ├── BusinessException.kt
│       └── GlobalExceptionHandler.kt
│
├── domain/                          # (신규) 도메인 패키지
│   └── event/
│       ├── controller/
│       │   └── EventController.kt   # REST API 엔드포인트
│       ├── dto/
│       │   ├── EventCreateRequest.kt # 생성 요청 DTO + Validation
│       │   └── EventResponse.kt     # 응답 DTO + from() 팩토리
│       ├── entity/
│       │   ├── Event.kt             # 이벤트 엔티티 (Rich Domain Model)
│       │   ├── EventStatus.kt       # 상태 enum
│       │   └── CouponIssue.kt       # 쿠폰 발급 엔티티 (스키마만)
│       ├── repository/
│       │   ├── EventRepository.kt   # JPA Repository
│       │   └── CouponIssueRepository.kt
│       └── service/
│           └── EventService.kt      # 비즈니스 로직
│
src/main/resources/
└── db/migration/
    ├── V1__create_event_table.sql
    └── V2__create_coupon_issue_table.sql
```

---

## 9. Implementation Order

> 각 단계는 이전 단계가 완료되어야 다음으로 진행할 수 있다.

| Step | Task | Files | 설명 |
|------|------|-------|------|
| 1 | uuid-creator 의존성 추가 | `build.gradle.kts` | `com.github.f4b6a3:uuid-creator` 추가 |
| 2 | Flyway 마이그레이션 | `V1__create_event_table.sql`, `V2__create_coupon_issue_table.sql` | UUID PK 스키마 |
| 3 | Entity + Enum | `Event.kt`, `EventStatus.kt`, `CouponIssue.kt` | UUID v7 + Persistable 패턴 |
| 4 | ErrorCode 추가 | `ErrorCode.kt` 수정 | `EVENT_NOT_FOUND`, `INVALID_EVENT_DATE` 추가 |
| 5 | Repository | `EventRepository.kt`, `CouponIssueRepository.kt` | `JpaRepository<Event, UUID>` |
| 6 | DTO | `EventCreateRequest.kt`, `EventResponse.kt` | id 타입 UUID 반영 |
| 7 | Service | `EventService.kt` | 비즈니스 로직 |
| 8 | Controller | `EventController.kt` | API 엔드포인트 |
| 9 | 통합 테스트 | Docker Compose 실행 후 Swagger UI 검증 | E2E 확인 |

---

## 10. Test Plan

### 10.1 Test Scope

| Type | Target | Tool | 비고 |
|------|--------|------|------|
| Integration Test | API 전체 흐름 | Testcontainers + MockMvc | `IntegrationTestBase` 활용 |
| Swagger 검증 | API 문서 정합성 | Swagger UI 수동 확인 | `/swagger-ui` |

### 10.2 Key Test Scenarios

- [ ] **Happy path**: 이벤트 생성 → 목록 조회 → 상세 조회 성공
- [ ] **Validation**: title 빈값, totalQuantity 0, startedAt > endedAt → 400 에러
- [ ] **Not Found**: 존재하지 않는 ID 조회 → 404 에러
- [ ] **Paging**: 여러 이벤트 생성 후 page/size 파라미터 동작 확인
- [ ] **Status Filter**: status=READY, status=OPEN 필터 동작 확인
- [ ] **Enum 에러**: 잘못된 status 값 → 400 에러 (GlobalExceptionHandler 처리)

---

## 11. Security Considerations

- [x] Bean Validation으로 입력값 검증 (XSS, SQL Injection 방지 — JPA parameterized query 사용)
- [ ] 인증/인가: Out of Scope (현재 단계에서는 미적용)
- [x] Entity 필드 `protected set`: 외부에서 직접 변경 불가
- [x] DTO 분리: Entity 내부 구조가 API 응답에 노출되지 않음

---

## Version History

| Version | Date | Changes | Author |
|---------|------|---------|--------|
| 0.1 | 2026-04-09 | Initial draft | beomjin |
| 0.2 | 2026-04-09 | UUID v7 ID 전략 적용 (UuidCreator + Persistable) | beomjin |
