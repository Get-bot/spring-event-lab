# Flash Sale (선착순 쿠폰/티켓팅) Roadmap

> **Summary**: Redis 원자적 연산 + Kafka 비동기 처리를 활용한 선착순 쿠폰 발급 시스템 - 전체 로드맵
>
> **Project**: spring-event-lab
> **Version**: 0.0.1-SNAPSHOT
> **Author**: beomjin
> **Date**: 2026-04-09
> **Status**: Roadmap (sub-feature별 개별 Plan으로 분할됨)
>
> ## Sub-Features
>
> | 순서 | Feature | Plan 문서 | 핵심 학습 |
> |:----:|---------|----------|----------|
> | 1 | event-crud | [01-event-crud.plan.md](01-event-crud.plan.md) | JPA Entity, Flyway, REST API |
> | 2 | redis-stock | [02-redis-stock.plan.md](02-redis-stock.plan.md) | Redis DECR 원자적 연산 |
> | 3 | concurrency-test | [03-concurrency-test.plan.md](03-concurrency-test.plan.md) | 동시성 테스트 |
> | 4 | kafka-consumer | [04-kafka-consumer.plan.md](04-kafka-consumer.plan.md) | Kafka 비동기 처리 |
> | 5 | waiting-queue | [05-waiting-queue.plan.md](05-waiting-queue.plan.md) | Redis Sorted Set 대기열 |

---

## Executive Summary

| Perspective | Content |
|-------------|---------|
| **Problem** | 선착순 이벤트 시 수만 건의 동시 요청이 DB에 직접 몰려 커넥션 풀 고갈, 데드락, 초과 발급(oversell) 등 장애 발생 |
| **Solution** | Redis DECR로 재고를 원자적으로 차감하고, Kafka로 DB 쓰기를 비동기 버퍼링하여 DB 부하를 제어하는 3-Layer 방어 구조 |
| **Function/UX Effect** | 사용자는 즉시(< 100ms) 발급 성공/실패 응답을 받고, 대기열을 통해 공정한 순서 보장 경험 |
| **Core Value** | DB 장애 없이 수만 TPS 트래픽을 안정적으로 처리하며, 정확한 수량 제어로 초과 발급 0건 보장 |

---

## 1. Overview

### 1.1 Purpose

순식간에 몰리는 대규모 스파이크 트래픽에서도 데이터베이스가 터지지 않고, 정확한 수량만큼만 선착순으로 쿠폰/티켓을 발급하는 시스템을 구축한다.

### 1.2 Background

- 선착순 이벤트는 짧은 시간(수 초)에 수만 건의 요청이 집중되는 대표적인 스파이크 트래픽 패턴
- DB만으로 처리 시: 커넥션 풀 고갈, 행 락 경합(Row Lock Contention), 데드락, 초과 발급(Oversell) 발생
- Redis + Kafka 조합으로 "빠른 판정(Redis) → 안정적 저장(Kafka → DB)" 파이프라인을 구성하여 해결
- 이 프로젝트의 기존 인프라(PostgreSQL + Redis + Kafka + Docker Compose)가 이미 세팅되어 있어 즉시 구현 가능

### 1.3 Related Documents

- 기존 인프라: `docker-compose.yml` (PostgreSQL 18, Redis 8, Kafka 4.2.0)
- 기존 설정: `application.yaml` (Kafka idempotent producer, Redis 설정 완료)
- 기존 코드: `global/` 패키지 (BaseEntity, ErrorCode, ExceptionHandler, Config 등)

---

## 2. Scope

### 2.1 In Scope

- [ ] **이벤트 관리**: 쿠폰/티켓 이벤트 CRUD (관리자 API)
- [ ] **Redis 재고 관리**: 이벤트 오픈 시 Redis에 수량 로드, 원자적 차감(DECR)
- [ ] **선착순 발급 API**: 유저 요청 → Redis 수량 확인/차감 → 즉시 응답
- [ ] **Kafka 비동기 처리**: 발급 성공 이벤트를 Kafka로 발행 → Consumer가 DB에 저장
- [ ] **대기열 시스템**: Redis Sorted Set 기반 대기열 (트래픽 과다 시 입장 제어)
- [ ] **중복 발급 방지**: 유저당 1회 발급 제한 (Redis Set + DB Unique 제약)
- [ ] **Resilience4j 서킷브레이커**: Redis/Kafka 장애 시 Fallback 처리
- [ ] **통합 테스트**: Testcontainers 기반 동시성 테스트

### 2.2 Out of Scope

- 사용자 인증/인가 시스템 (향후 별도 feature)
- 쿠폰 사용/결제 처리 (발급까지만)
- 프론트엔드 UI
- 모니터링 대시보드 (Grafana/Prometheus 연동)
- 멀티 노드 분산 배포 환경 고려

---

## 3. Requirements

### 3.1 Functional Requirements

| ID | Requirement | Priority | Status |
|----|-------------|----------|--------|
| FR-01 | 관리자가 이벤트(이름, 총 수량, 시작/종료 시간)를 생성할 수 있다 | High | Pending |
| FR-02 | 이벤트 시작 시 Redis에 재고 수량이 자동 로드된다 | High | Pending |
| FR-03 | 유저가 선착순 발급 API 호출 시 Redis DECR로 즉시 수량 차감 및 응답 | High | Pending |
| FR-04 | 수량 소진 시 즉시 "매진" 응답 반환 (DB 조회 없이) | High | Pending |
| FR-05 | 발급 성공 이벤트를 Kafka Topic으로 발행한다 | High | Pending |
| FR-06 | Kafka Consumer가 이벤트를 소비하여 DB에 발급 이력을 저장한다 | High | Pending |
| FR-07 | 동일 유저의 중복 발급을 방지한다 (Redis Set + DB Unique) | High | Pending |
| FR-08 | 대기열: 동시 접속 N명 초과 시 Sorted Set에 대기 → 순서대로 입장 | Medium | Pending |
| FR-09 | 이벤트 목록/상세 조회 API (잔여 수량 포함) | Medium | Pending |
| FR-10 | Kafka Consumer 실패 시 DLT(Dead Letter Topic)로 이동 후 재처리 | Medium | Pending |
| FR-11 | 이벤트 종료 후 Redis 재고와 DB 발급 건수 정합성 검증 배치 | Low | Pending |

### 3.2 Non-Functional Requirements

| Category | Criteria | Measurement Method |
|----------|----------|-------------------|
| Performance | 발급 API 응답 시간 < 100ms (p99) | Actuator + k6 부하테스트 |
| Throughput | 최소 10,000 TPS 동시 요청 처리 | k6 스크립트, Docker 환경 |
| Consistency | 초과 발급 0건 (Oversell Prevention) | 동시성 테스트 (100 스레드 × 100 요청) |
| Durability | Kafka Consumer 장애 시에도 메시지 유실 0건 | DLT + 재처리 로직 검증 |
| Availability | Redis 장애 시 Graceful Degradation | Resilience4j CircuitBreaker 테스트 |

---

## 4. Success Criteria

### 4.1 Definition of Done

- [ ] 선착순 발급 API가 정상 동작하며 초과 발급이 발생하지 않는다
- [ ] 1,000개 쿠폰에 10,000건 동시 요청 시 정확히 1,000건만 발급된다
- [ ] Kafka Consumer가 모든 발급 이벤트를 DB에 성공적으로 저장한다
- [ ] 중복 발급이 방지된다 (동일 유저 2회 요청 시 1건만 발급)
- [ ] 통합 테스트가 Testcontainers로 통과한다
- [ ] API 문서가 SpringDoc/Swagger UI에 노출된다

### 4.2 Quality Criteria

- [ ] 테스트 커버리지 80% 이상 (핵심 비즈니스 로직)
- [ ] 동시성 테스트 포함 (CountDownLatch + ExecutorService)
- [ ] Flyway 마이그레이션으로 스키마 관리
- [ ] 빌드 성공 (Gradle clean build)

---

## 5. Risks and Mitigation

| Risk | Impact | Likelihood | Mitigation |
|------|--------|------------|------------|
| Redis 장애 시 발급 불가 | High | Low | Resilience4j CircuitBreaker + Fallback (DB 직접 발급 with 비관적 락) |
| Kafka Consumer 지연으로 DB 저장 지연 | Medium | Medium | Consumer 병렬화 (파티션 증가), DLT 재처리 |
| Redis-DB 간 데이터 불일치 | High | Low | 이벤트 종료 후 정합성 검증 배치, Kafka idempotent producer |
| 대기열 순서 공정성 위반 | Medium | Low | Redis Sorted Set score = timestamp (밀리초) 사용 |
| Kafka 메시지 중복 소비 | Medium | Medium | Consumer 멱등성 보장 (DB Unique 제약 + upsert 패턴) |
| 이벤트 시작 시간 전 요청 유입 | Low | High | Redis에 이벤트 상태 저장, 시간 검증 로직 추가 |

---

## 6. Architecture Considerations

### 6.1 Project Level Selection

| Level | Characteristics | Recommended For | Selected |
|-------|-----------------|-----------------|:--------:|
| **Starter** | 단순 구조 | 정적 사이트, 포트폴리오 | |
| **Dynamic** | 기능 기반 모듈, BaaS 연동 | 일반 웹앱, MVP | |
| **Enterprise** | 엄격한 레이어 분리, DI, 고가용성 | 고트래픽 시스템, 복잡한 아키텍처 | **V** |

### 6.2 Key Architectural Decisions

| Decision | Options | Selected | Rationale |
|----------|---------|----------|-----------|
| Framework | Spring Boot 4.0 + Kotlin | Spring Boot 4.0 + Kotlin | 기존 프로젝트 스택 유지 |
| DB | PostgreSQL | PostgreSQL 18 | 기존 인프라, ACID 보장 |
| Cache/Queue | Redis | Redis 8 | 원자적 연산(DECR), Sorted Set 대기열 |
| Message Broker | Kafka | Kafka 4.2 (KRaft) | 비동기 처리, 내구성, idempotent producer |
| 분산 락 | Redisson / Lettuce | Redisson | 기존 의존성, 분산 락 편의성 |
| ORM | JPA + QueryDSL | JPA + QueryDSL 7.1 | 기존 설정 완료 |
| 마이그레이션 | Flyway | Flyway | 기존 설정 완료 |
| 서킷브레이커 | Resilience4j | Resilience4j | 기존 의존성 |
| API 문서 | SpringDoc | SpringDoc 3.0.2 | 기존 의존성 |
| 테스트 | JUnit5 + Testcontainers | Testcontainers | 기존 설정 (PostgreSQL, Redis, Kafka) |

### 6.3 System Architecture

```
┌──────────────┐     ┌──────────────────────────────────────────────┐
│   Client     │     │              Spring Boot Application          │
│  (k6/HTTP)   │     │                                              │
└──────┬───────┘     │  ┌─────────────┐    ┌────────────────────┐  │
       │             │  │  Controller  │───>│     Service        │  │
       │  HTTP       │  │  (API)       │    │  (Business Logic)  │  │
       │             │  └─────────────┘    └────────┬───────────┘  │
       │             │                              │              │
       ▼             │                    ┌─────────┴─────────┐    │
  ┌─────────┐        │                    ▼                   ▼    │
  │ Gateway │───────>│             ┌────────────┐    ┌──────────┐  │
  │ (8080)  │        │             │   Redis     │    │  Kafka   │  │
  └─────────┘        │             │  (재고차감)  │    │(Producer)│  │
                     │             └────────────┘    └────┬─────┘  │
                     │                                    │        │
                     │             ┌──────────────────────┘        │
                     │             ▼                                │
                     │  ┌──────────────────┐   ┌───────────────┐  │
                     │  │  Kafka Consumer   │──>│  PostgreSQL   │  │
                     │  │  (DB 저장)        │   │  (영구 저장)   │  │
                     │  └──────────────────┘   └───────────────┘  │
                     └──────────────────────────────────────────────┘
```

### 6.4 Request Flow (선착순 발급)

```
1. 유저 → POST /api/v1/events/{eventId}/issue
2. Controller → Service.issueCoupon(eventId, userId)
3. [중복 체크] Redis SISMEMBER coupon:issued:{eventId} {userId}
   └─ 이미 발급됨 → 409 Conflict 반환
4. [수량 차감] Redis DECR coupon:stock:{eventId}
   └─ 결과 < 0 → INCR 롤백 + 매진 응답 (410 Gone)
   └─ 결과 >= 0 → 발급 성공
5. [중복 방지] Redis SADD coupon:issued:{eventId} {userId}
6. [이벤트 발행] Kafka produce → topic: coupon-issue
7. → 유저에게 즉시 200 OK 응답 (발급 성공)

--- 비동기 ---

8. Kafka Consumer → coupon-issue topic 소비
9. DB INSERT: coupon_issue (event_id, user_id, issued_at)
10. 실패 시 → DLT (coupon-issue.DLT) 이동
```

### 6.5 대기열 Flow (선택적)

```
1. 유저 → POST /api/v1/events/{eventId}/enter
2. Redis ZADD waiting:{eventId} {timestamp} {userId}
3. Scheduler (매 N초) → ZPOPMIN waiting:{eventId} {batchSize}
4. 팝된 유저들에게 발급 프로세스 진행
5. 유저 → GET /api/v1/events/{eventId}/rank → 현재 대기 순번 응답
```

---

## 7. Convention Prerequisites

### 7.1 Existing Project Conventions

- [x] `global/common/` - BaseTimeEntity, BaseCreatedTimeEntity, PageResponse
- [x] `global/config/` - JpaConfig, AsyncConfig, RestClientConfig, SwaggerConfig
- [x] `global/exception/` - ErrorCode(enum), BusinessException, ErrorResponse, GlobalExceptionHandler
- [x] `application.yaml` - Kafka idempotent producer, JPA validate, Flyway 설정
- [x] Testcontainers 설정 (PostgreSQL, Kafka)
- [ ] Redis 관련 Config 클래스 (RedisConfig - 상태에 기록되어 있으나 파일 확인 필요)

### 7.2 Entity Template Convention

모든 JPA Entity는 아래 패턴을 따른다. (참고: Stock 엔티티 예시 기반)

**규칙:**
- **ID 전략**: UUID v7 사용 — `UuidCreator.getTimeOrderedEpoch()` (uuid-creator 라이브러리)
  - UUID v7은 타임스탬프 기반으로 시간순 정렬이 가능하고, 분산 환경에서 충돌 없는 고유 ID 생성
  - `Long` 자동 증가 대비 장점: 외부 노출 시 ID 추측 불가, 멀티 노드 환경에서 안전
  - `Persistable<UUID>` 인터페이스 구현 필요 (ID가 항상 non-null이므로 JPA isNew 판단을 위해)
- 생성자 파라미터로 필드를 받고, body에서 `var ... = param`으로 할당
- 모든 필드에 `protected set` 적용 (외부에서 직접 변경 불가, 도메인 메서드로만 변경)
- `@Column`에 `nullable`, `length`, `comment` 등 상세 속성 명시
- `BaseTimeEntity()` 상속 (createdAt, updatedAt 자동 관리)
- `@Table(name = "...")` 으로 테이블명 명시

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
    val id: UUID = UuidCreator.getTimeOrderedEpoch()

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

### 7.3 Flyway Migration Convention

- **마이그레이션 도구**: Flyway (설정 완료: `application.yaml`)
- **파일 위치**: `src/main/resources/db/migration/`
- **네이밍 규칙**: `V{번호}__{설명}.sql` (언더스코어 2개)
  - 예: `V1__create_event_table.sql`, `V2__create_coupon_issue_table.sql`
- **DDL 규칙**:
  - PK는 `UUID PRIMARY KEY` (애플리케이션에서 UUID v7 생성, DB 기본값 불필요)
  - FK 참조 컬럼도 `UUID` 타입 사용
  - `created_at`, `updated_at`는 BaseTimeEntity가 관리하므로 DDL에 포함
  - `VARCHAR` 길이는 Entity `@Column(length=N)`과 일치시킬 것
  - `COMMENT ON COLUMN`으로 컬럼 설명 추가 (Entity `comment` 속성과 동기화)
- **JPA ddl-auto**: `validate` (Flyway가 DDL 관리, JPA는 검증만)

### 7.4 Conventions to Define/Verify

| Category | Current State | To Define | Priority |
|----------|---------------|-----------|:--------:|
| **패키지 구조** | global 패키지만 존재 | 도메인별 패키지 구조 정의 | High |
| **Naming** | Kotlin 표준 | Entity, DTO, Service 네이밍 규칙 | High |
| **Entity 패턴** | 미정의 | 생성자 파라미터 + protected set 패턴 | High |
| **Flyway** | 설정 완료 | V{N}__ 네이밍, COMMENT ON COLUMN | High |
| **에러 코드** | 범용 ErrorCode enum | Flash Sale 전용 에러 코드 추가 | Medium |
| **Kafka Topic 네이밍** | 미정의 | `{domain}.{action}` 형식 | Medium |
| **Redis Key 네이밍** | 미정의 | `{domain}:{type}:{id}` 형식 | Medium |

### 7.5 Package Structure (제안)

```
com.beomjin.springeventlab/
├── global/                          # (기존) 공통 인프라
│   ├── common/
│   ├── config/
│   └── exception/
├── domain/
│   └── event/                       # Flash Sale 도메인
│       ├── controller/              # REST API
│       │   └── EventController.kt
│       ├── dto/                     # Request/Response DTO
│       │   ├── EventCreateRequest.kt
│       │   ├── EventResponse.kt
│       │   └── IssueResponse.kt
│       ├── entity/                  # JPA Entity
│       │   ├── Event.kt
│       │   └── CouponIssue.kt
│       ├── repository/             # JPA Repository
│       │   ├── EventRepository.kt
│       │   └── CouponIssueRepository.kt
│       ├── service/                # Business Logic
│       │   ├── EventService.kt
│       │   └── CouponIssueService.kt
│       └── infrastructure/         # Redis, Kafka 연동
│           ├── RedisStockManager.kt
│           ├── WaitingQueueManager.kt
│           ├── CouponIssueProducer.kt
│           └── CouponIssueConsumer.kt
└── infra/                          # 인프라 공통
    ├── kafka/
    │   └── KafkaConfig.kt
    └── redis/
        └── RedisConfig.kt
```

### 7.6 Environment Variables

| Variable | Purpose | Scope | Status |
|----------|---------|-------|:------:|
| `DB_USERNAME` | DB 접속 계정 | Server | Exists |
| `DB_PASSWORD` | DB 접속 비밀번호 | Server | Exists |
| `REDIS_HOST` | Redis 호스트 | Server | Exists |
| `REDIS_PORT` | Redis 포트 | Server | Exists |
| `KAFKA_BOOTSTRAP_SERVERS` | Kafka 브로커 주소 | Server | Exists |

### 7.7 Database Schema (초안)

```sql
-- V1__create_event_table.sql
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

-- V2__create_coupon_issue_table.sql
CREATE TABLE coupon_issue (
    id          UUID            PRIMARY KEY,
    event_id    UUID            NOT NULL REFERENCES event(id),
    user_id     BIGINT          NOT NULL,
    created_at  TIMESTAMP       NOT NULL DEFAULT NOW(),
    CONSTRAINT uk_coupon_issue UNIQUE (event_id, user_id)
);

CREATE INDEX idx_coupon_issue_event_id ON coupon_issue(event_id);
CREATE INDEX idx_coupon_issue_user_id ON coupon_issue(user_id);
```

### 7.8 Redis Key Design

| Key Pattern | Type | Purpose | TTL |
|-------------|------|---------|-----|
| `coupon:stock:{eventId(UUID)}` | String (integer) | 잔여 수량 (DECR) | 이벤트 종료 + 1h |
| `coupon:issued:{eventId(UUID)}` | Set | 발급된 userId 집합 (중복 방지) | 이벤트 종료 + 1h |
| `waiting:{eventId(UUID)}` | Sorted Set | 대기열 (score=timestamp) | 이벤트 종료 + 1h |

### 7.9 Kafka Topic Design

| Topic | Partitions | Purpose | Consumer Group |
|-------|-----------|---------|----------------|
| `coupon-issue` | 3 | 쿠폰 발급 이벤트 | `spring-event-lab` |
| `coupon-issue.DLT` | 1 | Dead Letter Topic | `spring-event-lab-dlt` |

---

## 8. Next Steps

1. [ ] Design 문서 작성 (`flash-sale.design.md`) - 상세 클래스 다이어그램, 시퀀스 다이어그램
2. [ ] DB 마이그레이션 스크립트 작성 (Flyway)
3. [ ] Entity, Repository 구현
4. [ ] Redis 재고 관리 로직 구현
5. [ ] 선착순 발급 API + Kafka Producer 구현
6. [ ] Kafka Consumer + DLT 처리 구현
7. [ ] 대기열 시스템 구현
8. [ ] 동시성 통합 테스트 작성
9. [ ] 부하 테스트 (k6)

---

## Version History

| Version | Date | Changes | Author |
|---------|------|---------|--------|
| 0.1 | 2026-04-09 | Initial draft | beomjin |
