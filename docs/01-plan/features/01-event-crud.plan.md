# Event CRUD Planning Document

> **Summary**: 선착순 이벤트의 기반이 되는 이벤트 엔티티 설계와 관리자용 CRUD API 구현
>
> **Project**: spring-event-lab
> **Version**: 0.0.1-SNAPSHOT
> **Author**: beomjin
> **Date**: 2026-04-09
> **Status**: Draft
> **Roadmap**: [flash-sale.plan.md](flash-sale.plan.md) (1/5)

---

## Executive Summary

| Perspective | Content |
|-------------|---------|
| **Problem** | 선착순 발급 시스템의 기반이 되는 이벤트(쿠폰/티켓) 데이터 구조와 관리 API가 없음 |
| **Solution** | JPA Entity + Flyway 마이그레이션으로 이벤트 테이블을 설계하고, REST API로 CRUD 제공 |
| **Function/UX Effect** | 관리자가 이벤트를 생성/조회/수정할 수 있으며, 이벤트 상태(READY/OPEN/CLOSED)를 관리 |
| **Core Value** | 이후 Redis, Kafka 등 고급 기능의 토대가 되는 안정적인 도메인 모델 확립 |

---

## 1. Overview

### 1.1 Purpose

선착순 쿠폰/티켓 발급 시스템의 기반이 되는 **이벤트 도메인**을 설계한다.
이벤트 생성, 조회, 상태 관리 등 기본 CRUD를 먼저 구현하여, 이후 Redis/Kafka 연동의 토대를 만든다.

### 1.2 학습 포인트

- **UUID v7 ID 전략**: 타임스탬프 기반 정렬 가능 UUID, `UuidCreator.getTimeOrderedEpoch()`
- **JPA Entity 설계**: BaseTimeEntity 상속, enum 매핑, 제약 조건
- **Flyway 마이그레이션**: DDL을 코드로 관리하는 방법
- **Layered Architecture**: Controller → Service → Repository 구조
- **DTO 패턴**: Entity를 직접 노출하지 않는 이유
- **Validation**: Bean Validation으로 입력값 검증

---

## 2. Scope

### 2.1 In Scope

- [ ] Event 엔티티 설계 (title, totalQuantity, issuedQuantity, status, startedAt, endedAt)
- [ ] CouponIssue 엔티티 설계 (eventId, userId, issuedAt) - 스키마만, 로직은 다음 feature
- [ ] Flyway 마이그레이션 스크립트 (V1, V2)
- [ ] Event CRUD API (생성, 목록 조회, 상세 조회)
- [ ] 이벤트 상태 관리 (READY → OPEN → CLOSED)
- [ ] 도메인 전용 ErrorCode 추가
- [ ] Request/Response DTO + Validation
- [ ] Swagger API 문서

### 2.2 Out of Scope

- 쿠폰 발급 로직 (redis-stock에서 구현)
- Redis 연동
- Kafka 연동
- 인증/인가

---

## 3. Requirements

### 3.1 Functional Requirements

| ID | Requirement | Priority | Status |
|----|-------------|----------|--------|
| FR-01 | 이벤트 생성 API: title, totalQuantity, startedAt, endedAt 입력 | High | Pending |
| FR-02 | 이벤트 목록 조회 API: 페이징, 상태 필터 | High | Pending |
| FR-03 | 이벤트 상세 조회 API: 잔여 수량 포함 | High | Pending |
| FR-04 | 이벤트 상태 enum: READY, OPEN, CLOSED | High | Pending |
| FR-05 | Flyway 마이그레이션으로 스키마 생성 | High | Pending |
| FR-06 | 입력값 검증 (title 빈값, 수량 0 이하, 시작일 > 종료일 등) | Medium | Pending |

### 3.2 Non-Functional Requirements

| Category | Criteria | Measurement Method |
|----------|----------|-------------------|
| Maintainability | 도메인 패키지 구조 준수 | 코드 리뷰 |
| Documentation | Swagger UI에 API 문서 노출 | /swagger-ui 접속 확인 |

---

## 4. Entity Design (Template Convention)

> 프로젝트 전체 엔티티 규칙은 [flash-sale.plan.md - 7.2 Entity Template Convention](flash-sale.plan.md)을 따른다.
> - 생성자 파라미터로 필드를 받고, body에서 `var ... = param` + `protected set`
> - `@Column`에 `nullable`, `length`, `comment` 등 상세 속성 명시
> - `BaseTimeEntity()` 상속 (createdAt, updatedAt 자동 관리)

### 4.1 Event Entity

```kotlin
@Entity
@Table(name = "event")
class Event(
    title: String,
    totalQuantity: Int,
    eventStatus: EventStatus,
    startedAt: LocalDateTime,
    endedAt: LocalDateTime,
) : BaseTimeEntity() {

    @Id
    @Column(columnDefinition = "UUID")
    val id: UUID = UuidCreator.getTimeOrderedEpoch()  // UUID v7 (시간순 정렬 가능)

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

    @Column(nullable = false, comment = "시작 시각")
    var startedAt: LocalDateTime = startedAt
        protected set

    @Column(nullable = false, comment = "종료 시각")
    var endedAt: LocalDateTime = endedAt
        protected set
}
```

### 4.2 EventStatus Enum

```kotlin
enum class EventStatus {
    READY,  // 이벤트 준비 중 (시작 전)
    OPEN,   // 이벤트 진행 중 (발급 가능)
    CLOSED  // 이벤트 종료
}
```

### 4.3 CouponIssue Entity

```kotlin
@Entity
@Table(name = "coupon_issue")
class CouponIssue(
    eventId: UUID,
    userId: Long,
) : BaseCreatedTimeEntity() {

    @Id
    @Column(columnDefinition = "UUID")
    val id: UUID = UuidCreator.getTimeOrderedEpoch()

    @Column(nullable = false, comment = "이벤트 ID")
    var eventId: UUID = eventId
        protected set

    @Column(nullable = false, comment = "사용자 ID")
    var userId: Long = userId
        protected set
}
```

## 5. Flyway Migration (DDL)

> 마이그레이션 도구: **Flyway** | 파일 위치: `src/main/resources/db/migration/`
> JPA `ddl-auto: validate` — Flyway가 DDL 관리, JPA는 검증만 수행

### 5.1 V1__create_event_table.sql

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

COMMENT ON COLUMN event.title IS '이벤트 제목';
COMMENT ON COLUMN event.total_quantity IS '총 수량';
COMMENT ON COLUMN event.issued_quantity IS '발급된 수량';
COMMENT ON COLUMN event.event_status IS '이벤트 상태 (READY/OPEN/CLOSED)';
COMMENT ON COLUMN event.started_at IS '시작 시각';
COMMENT ON COLUMN event.ended_at IS '종료 시각';
```

> **UUID v7과 PostgreSQL**: PostgreSQL `UUID` 타입은 16바이트 고정 크기로 저장된다. UUID v7은 앞부분이 타임스탬프이므로 B-tree 인덱스에서 순차 삽입 패턴을 보이며, UUID v4 대비 인덱스 성능이 우수하다. `DEFAULT` 절이 없는 이유는 애플리케이션(Hibernate)에서 UUID를 생성하기 때문이다.

### 5.2 V2__create_coupon_issue_table.sql

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

COMMENT ON COLUMN coupon_issue.event_id IS '이벤트 ID';
COMMENT ON COLUMN coupon_issue.user_id IS '사용자 ID';
```

---

## 6. Package Structure

```
domain/event/
├── controller/
│   └── EventController.kt          # REST API
├── dto/
│   ├── EventCreateRequest.kt       # 생성 요청
│   ├── EventResponse.kt            # 응답
│   └── EventStatusRequest.kt       # 상태 변경 요청
├── entity/
│   ├── Event.kt                    # 이벤트 엔티티
│   ├── EventStatus.kt              # 상태 enum
│   └── CouponIssue.kt              # 쿠폰 발급 엔티티 (스키마만)
├── repository/
│   ├── EventRepository.kt          # JPA Repository
│   └── CouponIssueRepository.kt    # JPA Repository
└── service/
    └── EventService.kt             # 비즈니스 로직
```

---

## 7. API Design

| Method | Endpoint | Description |
|--------|----------|-------------|
| POST | `/api/v1/events` | 이벤트 생성 |
| GET | `/api/v1/events` | 이벤트 목록 조회 (페이징) |
| GET | `/api/v1/events/{id}` | 이벤트 상세 조회 |

---

## 8. Success Criteria

- [ ] Flyway 마이그레이션(`V1`, `V2`)이 정상 실행된다
- [ ] Entity가 Template Convention(생성자 파라미터 + protected set)을 따른다
- [ ] CRUD API가 Swagger UI에서 테스트 가능하다
- [ ] Validation 에러 시 적절한 에러 응답이 반환된다
- [ ] 기존 global 패키지의 ErrorCode, BaseTimeEntity를 활용한다

---

## 9. Next Steps

1. [ ] Design 문서 작성 (`event-crud.design.md`)
2. [ ] 구현
3. [ ] 다음 feature: `redis-stock`

---

## Version History

| Version | Date | Changes | Author |
|---------|------|---------|--------|
| 0.1 | 2026-04-09 | Initial draft | beomjin |
| 0.2 | 2026-04-09 | UUID v7 ID 전략 적용 (UuidCreator) | beomjin |
