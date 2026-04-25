# JPA Writing Guide — BuWs API Server

> **Tech Stack**: Spring Boot 4.0.5 / Spring Data JPA / Kotlin 2.3.20 / PostgreSQL 18 / Flyway / QueryDSL (openfeign fork, KSP) / `kotlin("plugin.jpa")` + `kotlin("plugin.allopen")`
> **Origin**: `Event` Entity + `DateRange` VO + `User` PDCA(2026-04) 패턴 정리
> **Last Updated**: 2026-04-20 (§8.4 Flyway 코멘트 규약 추가)

본 문서는 BuWs API Server의 **Spring Data JPA + Kotlin 표준 패턴**을 정리합니다. CLAUDE.md의 Entity 컨벤션 4줄을 풀어쓴 deep-dive 가이드입니다.

---

## 목차

1. [Repository — `findByIdOrNull` 기본 패턴](#1-repository--findbyidornull-기본-패턴)
2. [Spring Data Kotlin Extensions](#2-spring-data-kotlin-extensions)
3. [Entity 설계 — `protected set` + `init` 불변식](#3-entity-설계--protected-set--init-불변식)
4. [Kotlin JPA 플러그인 (`plugin.jpa` + `plugin.allopen`)](#4-kotlin-jpa-플러그인-pluginjpa--pluginallopen)
5. [ID 전략 — UUID v7 + `@JdbcTypeCode`](#5-id-전략--uuid-v7--jdbctypecode)
6. [Aggregate 경계 — `@ManyToOne` 지양, ID 참조](#6-aggregate-경계--manytoone-지양-id-참조)
7. [JPA Auditing — `@CreatedDate` / `@LastModifiedDate`](#7-jpa-auditing--createddate--lastmodifieddate)
8. [Flyway 마이그레이션](#8-flyway-마이그레이션)
9. [트랜잭션 (`@Transactional`)](#9-트랜잭션-transactional)
10. [QueryDSL 짧은 가이드](#10-querydsl-짧은-가이드)
11. [트러블슈팅 / FAQ](#11-트러블슈팅--faq)

---

## 1. Repository — `findByIdOrNull` 기본 패턴

**핵심 규칙**: 단건 조회는 항상 `findByIdOrNull` + Elvis 연산자(`?:`) 조합. **Java `Optional.orElseThrow` 사용 금지** (Kotlin 관용 위반).

```kotlin
import org.springframework.data.repository.findByIdOrNull
import com.bubaum.buws.global.exception.BusinessException
import com.bubaum.buws.global.exception.ErrorCode

@Service
@Transactional(readOnly = true)
class UserService(private val userRepository: UserRepository) {
    fun findById(id: UUID): UserInfoResponse {
        val user = userRepository.findByIdOrNull(id)
            ?: throw BusinessException(ErrorCode.USER_NOT_FOUND, "id=$id")
        return UserInfoResponse.from(user)
    }
}
```

### 왜 `findByIdOrNull`인가

| 측면 | `findById(id).orElseThrow { ... }` (Java 패턴) | `findByIdOrNull(id) ?: throw ...` (Kotlin 관용) |
|------|-----------------------------------------------|------------------------------------------------|
| 반환 타입 | `Optional<T>` (Java 호환 wrapper) | `T?` (Kotlin null safety, type system에 내장) |
| 코드 길이 | `.orElseThrow { ... }` | `?: throw ...` (짧음) |
| Kotlin 관용성 | ❌ Optional은 Java가 nullable을 표현 못 해 만든 wrapper. Kotlin은 `T?`로 직접 표현 가능 → redundant | ✅ Kotlin 관용 |
| `findByLoginId` 등 derive query와 일관성 | ❌ 어떤 조회는 `Optional`, 어떤 조회는 `T?` 혼재 | ✅ 모두 `T?` 통일 |
| 추가 의존성 | 없음 | 없음 (`org.springframework.data.repository.findByIdOrNull` extension) |

**결론**: 단건 조회는 항상 `findByIdOrNull` + `?:` 패턴.

### Repository 인터페이스 작성

```kotlin
interface UserRepository : JpaRepository<User, UUID> {
    fun findByLoginId(loginId: String): User?              // derive query, nullable 반환
    fun existsByLoginId(loginId: String): Boolean
    fun existsByPhone(phone: String): Boolean
    fun existsByEmail(email: String): Boolean
    // findByIdOrNull은 extension이므로 별도 선언 불필요 (JpaRepository.findById만 있으면 됨)
}
```

| Spring Data 메서드 | 반환 | 사용 패턴 |
|------------------|------|-----------|
| `findByIdOrNull(id)` | `T?` | `?: throw ...` |
| `findByX(value)` (derive) | `T?` | `?: throw ...` |
| `existsByX(value)` | `Boolean` | `if (existsByX(...)) throw ...` |
| `findAll()` / `findAll(pageable)` | `List<T>` / `Page<T>` | 직접 사용 |
| `getReferenceById(id)` | `T` (proxy) | 영속성 컨텍스트의 lazy proxy 필요 시만 |

---

## 2. Spring Data Kotlin Extensions

본 프로젝트에서 활용 가능한 Kotlin extension 목록:

```kotlin
import org.springframework.data.repository.findByIdOrNull          // findById → T?
import org.springframework.data.repository.getByIdOrNull            // (Spring Data 3.5+)
```

`findByIdOrNull`은 가장 자주 쓰입니다. 그 외 `findOne(spec) ?: ...` 같은 Specification 호출 시에도 nullable 패턴 유지.

---

## 3. Entity 설계 — `protected set` + `init` 불변식

### 3.1 표준 Entity 구조

```kotlin
@Entity
@Table(name = "users")
class User(
    id: UUID = UuidCreator.getTimeOrderedEpoch(),      // 생성자 파라미터로 받음
    loginId: String,
    phone: String,
    // ...
) : BaseTimeEntity() {

    @Id
    @JdbcTypeCode(SqlTypes.UUID)
    var id: UUID = id
        protected set                                   // JPA reflection 허용 + 외부 변경 차단

    @Column(name = "login_id", nullable = false, unique = true, length = 30)
    var loginId: String = loginId
        protected set

    @Column(nullable = false, unique = true, length = 16)
    var phone: String = phone
        protected set
    // ...

    init {
        require(UserPattern.LOGIN_ID.matches(loginId)) { "invalid loginId format" }
        require(UserPattern.PHONE.matches(phone))      { "invalid phone format" }
        // ... 모든 도메인 불변식
    }

    fun changePassword(newEncodedPassword: String) {    // 상태 전이는 Entity 메서드
        require(newEncodedPassword.isNotBlank())
        this.encodedPassword = newEncodedPassword
    }
}
```

### 3.2 패턴 체크리스트

- [ ] **주 생성자 파라미터 + `var` 필드 + `protected set`** — JPA가 reflection으로 set 가능 + 외부 setter 호출 차단
- [ ] **`init { require(...) }` 블록**에 도메인 불변식 — 객체 생성 fail-fast
- [ ] **상태 전이는 Entity 메서드**(`changePassword`, `open`, `close`, `issue`) — Service에 로직 누적 방지 (Anemic 안티패턴)
- [ ] **`BaseTimeEntity` 상속** — `createdAt`, `updatedAt` Auditing
- [ ] **`@Column` 명시** — `nullable`, `unique`, `length` 모두 설정 (`ddl-auto: validate` 통과 위해)
- [ ] **`@Table` `name` 명시** — Kotlin 클래스명과 DB 테이블명 분리 (snake_case 변환 자동 안 됨)

### 3.3 Value Object — `@Embeddable`

```kotlin
@Embeddable
class DateRange(
    startedAt: Instant,
    endedAt: Instant,
) {
    @Column(name = "started_at", nullable = false)
    var startedAt: Instant = startedAt
        protected set
    // ...

    init {
        if (!startedAt.isBefore(endedAt)) {
            throw BusinessException(ErrorCode.INVALID_DATE_RANGE, "startedAt=$startedAt, endedAt=$endedAt")
        }
    }

    fun contains(instant: Instant): Boolean = !instant.isBefore(startedAt) && instant.isBefore(endedAt)

    override fun equals(other: Any?): Boolean { /* ... value equality */ }
    override fun hashCode(): Int { /* ... */ }
}
```

VO 규칙:
- `@Embeddable` + 같은 패턴 (`var` + `protected set` + `init` 불변식)
- `equals`/`hashCode` override (value equality — Entity는 ID equality)
- 도메인 메서드는 VO 자체에 (`DateRange.contains(...)`)

---

## 4. Kotlin JPA 플러그인 (`plugin.jpa` + `plugin.allopen`)

### 4.1 build.gradle.kts

```kotlin
plugins {
    kotlin("plugin.jpa") version "2.3.20"        // no-arg constructor 자동 생성
    kotlin("plugin.allopen") version "2.3.20"    // @Entity 클래스를 open으로 (Hibernate proxy 위해)
}

allOpen {
    annotation("jakarta.persistence.Entity")
    annotation("jakarta.persistence.Embeddable")
    annotation("jakarta.persistence.MappedSuperclass")
}
```

### 4.2 무엇을 자동으로 해주는가

| 플러그인 | 역할 | 없으면? |
|----------|------|---------|
| `plugin.jpa` | `@Entity`/`@Embeddable`에 **protected no-arg constructor 자동 생성** | JPA reflection 인스턴스화 실패 (`InstantiationException`) |
| `plugin.allopen` | `@Entity` 클래스를 **`open`으로 변환** (Kotlin 기본 `final` 회피) | Hibernate가 lazy loading proxy 생성 불가 |

**Kotlin 클래스 기본은 `final`이고 생성자에 모든 필드가 와야 함** — JPA의 두 핵심 요구사항(no-arg + 상속 가능)과 충돌. 두 플러그인이 이를 자동 해결.

---

## 5. ID 전략 — UUID v7 + `@JdbcTypeCode`

### 5.1 표준 패턴

```kotlin
@Id
@JdbcTypeCode(SqlTypes.UUID)
var id: UUID = UuidCreator.getTimeOrderedEpoch()      // 앱 생성 (DB AUTO 사용 안 함)
    protected set
```

### 5.2 왜 UUID v7

| 측면 | UUID v4 (랜덤) | **UUID v7 (시간 순서)** | Long IDENTITY |
|------|----------------|------------------------|---------------|
| 정렬성 | 랜덤 → DB 인덱스 페이지 분산 (B-Tree degradation) | **시간 순서** → 인덱스 locality 우수 | 순차, 가장 빠름 |
| 분산 환경 | 충돌 거의 없음 | 충돌 거의 없음 | 단일 시퀀스 → 분산 시 병목 |
| URL 안전성 | `-` 포함, 길이 큼 | 동일 | 짧음 (단점: enumeration 가능) |
| 보안 (열거 방어) | ✅ | ✅ | ❌ 순차 노출 |
| 본 프로젝트 채택 | - | **✅** | - |

`UuidCreator.getTimeOrderedEpoch()`는 **`com.github.f4b6a3:uuid-creator`** 라이브러리. UUID v7 spec 준수.

### 5.3 `@JdbcTypeCode(SqlTypes.UUID)` 필수

PostgreSQL 18 기본 컬럼 타입은 `UUID` (binary 16 bytes). Hibernate가 이를 알 수 있도록 `@JdbcTypeCode` 명시.
- 누락 시: VARCHAR(36)로 저장 → 인덱스 효율↓ + 마이그레이션 시 깨짐.

### 5.4 앱 생성 vs DB 생성

본 프로젝트는 **앱에서 ID 생성** (`UuidCreator.getTimeOrderedEpoch()` 기본값):
- 트랜잭션 시작 전에 ID 확정 → 외부 호출(이벤트 발행 등)에 사용 가능
- DB AUTO_INCREMENT 의존 없음 → 분산 환경 대응

---

## 6. Aggregate 경계 — `@ManyToOne` 지양, ID 참조

### 6.1 핵심 규칙

서로 다른 Aggregate Root 간에는 **`@ManyToOne` 사용 금지**. ID 필드(UUID)로만 참조.

```kotlin
// ❌ Anti-pattern — Aggregate 경계 침범
@Entity
class CouponIssue(
    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "event_id")
    val event: Event,
    // ...
)

// ✅ Right — ID 참조만
@Entity
class CouponIssue(
    @Column(name = "event_id", nullable = false)
    @JdbcTypeCode(SqlTypes.UUID)
    val eventId: UUID,
    // ...
)
```

### 6.2 이유

| 사유 | 설명 |
|------|------|
| **DDD Aggregate 경계 준수** | 한 Aggregate를 수정할 때 다른 Aggregate 인스턴스를 가져올 필요 없음 → 트랜잭션 경계 명확 |
| **N+1 방지** | `@ManyToOne` lazy loading은 collection 순회 시 N+1 query 발생 가능. ID 참조는 조인이 명시적 |
| **선착순/높은 동시성 환경 성능** | Aggregate 인스턴스화 비용 절감 (Hibernate proxy 생성 회피) |
| **테스트 단순화** | Mock 시 다른 Aggregate fixture 의존성 제거 |

### 6.3 조회 시 명시적 조합

조회에서 두 Aggregate가 모두 필요하면 Service에서 명시 호출:

```kotlin
@Service
class CouponIssueService(
    private val couponIssueRepository: CouponIssueRepository,
    private val eventRepository: EventRepository,
) {
    @Transactional(readOnly = true)
    fun getIssueWithEvent(issueId: UUID): IssueWithEventResponse {
        val issue = couponIssueRepository.findByIdOrNull(issueId)
            ?: throw BusinessException(ErrorCode.NOT_FOUND)
        val event = eventRepository.findByIdOrNull(issue.eventId)
            ?: throw BusinessException(ErrorCode.EVENT_NOT_FOUND)
        return IssueWithEventResponse.of(issue, event)
    }
}
```

명시적 = 두 query가 코드에 보임 = 성능 고려 명확.

---

## 7. JPA Auditing — `@CreatedDate` / `@LastModifiedDate`

### 7.1 활성화

```kotlin
// global/config/JpaAuditingConfig.kt 또는 BuwsApiServerApplication
@SpringBootApplication
@EnableJpaAuditing
class BuwsApiServerApplication
```

### 7.2 BaseTimeEntity

```kotlin
@MappedSuperclass
@EntityListeners(AuditingEntityListener::class)
abstract class BaseTimeEntity {
    @CreatedDate
    @Column(name = "created_at", nullable = false, updatable = false)
    var createdAt: Instant? = null
        protected set

    @LastModifiedDate
    @Column(name = "updated_at")
    var updatedAt: Instant? = null
        protected set
}
```

### 7.3 nullable 처리 주의

- `createdAt: Instant?` (nullable)이지만 영속화 후엔 `@PrePersist`로 채워짐
- DTO 변환 시: `requireNotNull(entity.createdAt) { "JPA Auditing must be enabled" }` — NPE 시 즉시 원인 진단

```kotlin
// UserInfoResponse.from(user)
createdAt = requireNotNull(user.createdAt) { "createdAt must be set by JPA Auditing" }
```

`@EnableJpaAuditing`이 활성되지 않으면 영속화해도 `createdAt = null` → `requireNotNull`이 catch.

---

## 8. Flyway 마이그레이션

### 8.1 명명 규칙

```
src/main/resources/db/migration/V{YYYYMMDDHHMMSS}__{description}.sql
```

예시:
```
V20260418100000__create_users_table.sql
V20260418110000__add_users_phone_index.sql
```

- `V` prefix (대문자), 버전(타임스탬프 권장), `__` (double underscore), 설명, `.sql`
- 동일 버전 두 파일 → 부팅 실패. 타임스탬프 권장.

### 8.2 부팅 시 자동 실행

```yaml
# application.yml
spring:
  jpa:
    hibernate:
      ddl-auto: validate    # Entity ↔ 스키마 불일치 부팅 시 감지
  flyway:
    enabled: true            # 기본값 true
```

`spring-boot-starter-flyway` 의존성 필수 — `flyway-core`만으로는 auto-config 안 됨 (CLAUDE.md 명시 주의사항).

### 8.3 `ddl-auto: validate`의 가치

- **Entity 추가/변경 시 마이그레이션 누락 → 부팅 실패** → 운영 환경 안전망
- 개발 중 실수 방지

### 8.4 코멘트 규약 — 파일 헤더 필수

모든 Flyway 마이그레이션 파일은 **맨 위에 헤더 코멘트**를 둔다. "왜 이 변경이 필요한가"를 파일 단독으로 추적 가능하게 하기 위함.

```sql
-- V20260419000000__create_user_role_assignments.sql
--
-- Purpose: 백오피스 운영자 역할(RBAC) 매핑 테이블 신설.
-- Source:  docs/01-plan/features/auth-rbac.plan.md §8.1, v0.11
-- Design:  docs/02-design/features/auth-rbac.design.md §5.1
-- Note:    Role은 코드 enum(com.bubaum.buws.auth.authorization.Role)으로 고정.
--          CHECK 제약으로 화이트리스트 외 값 삽입을 차단.
--          Permission 매트릭스는 Phase 2로 연기 (Plan §2.2 Out of Scope).

CREATE TABLE user_role_assignments (
    user_id     UUID         NOT NULL REFERENCES users(id) ON DELETE CASCADE,
    role        VARCHAR(32)  NOT NULL,
    created_at  TIMESTAMPTZ  NOT NULL,
    assigned_by UUID,
    PRIMARY KEY (user_id, role),
    CONSTRAINT uk_user_role_assignments_role_whitelist
        CHECK (role IN ('ADMIN', 'OPERATOR', 'WITHDRAWAL_APPROVER'))
);
```

#### 필수 헤더 4요소

| 항목 | 내용 | 예시 |
|------|------|------|
| **파일명 echo** (1행) | 맨 첫 줄에 파일명을 그대로 반복 | `-- V20260419000000__create_user_role_assignments.sql` |
| **`Purpose`** (1-2행) | 비즈니스 관점의 변경 의도 | `Purpose: 백오피스 운영자 역할(RBAC) 매핑 테이블 신설.` |
| **`Source`** | 상위 출처 (Plan/Design 경로 + §번호 + 버전) | `Source: docs/01-plan/features/auth-rbac.plan.md §8.1, v0.11` |
| **`Note`** (필요 시) | 비자명한 선택 근거 — NOT NULL / DEFAULT / INDEX / CHECK 판단 | `Note: CHECK 제약으로 화이트리스트 외 값 삽입 차단.` |

#### DB 레벨 `COMMENT ON TABLE/COLUMN` (권장)

데이터 딕셔너리에 남겨 DB 콘솔(`\d+ table_name`)에서도 보이게 한다.

```sql
COMMENT ON TABLE user_role_assignments IS
    '사용자 ↔ 역할 매핑 (Append-only). auth-rbac.design.md §5.1';

COMMENT ON COLUMN user_role_assignments.role IS
    'ADMIN | OPERATOR | WITHDRAWAL_APPROVER — CHECK 제약으로 enum 확장 시 마이그레이션 강제';

COMMENT ON COLUMN user_role_assignments.assigned_by IS
    'NULL = signup 자동 부여, NOT NULL = 관리자 콘솔에서 AuditorAware로 주입';
```

**SQL 파일 코멘트 vs DB COMMENT — 역할 분리**:
| 항목 | SQL 파일 코멘트 | DB `COMMENT ON` |
|------|----------------|-----------------|
| 답하는 질문 | **왜 이 변경을 했나** (변경 이력) | **지금 이 컬럼이 무엇인가** (현재 상태) |
| 보이는 경로 | 파일 열람, Git history, IDE grep | `psql \d+`, DB IDE, information_schema |
| 영속성 | 파일과 함께 | DB 백업/복원 시 따라감 |
| 필수 여부 | **필수** (헤더 4요소) | 권장 (테이블 + 주요 컬럼) |

#### 왜 Flyway 코멘트가 Git log보다 중요한가

- **Rollback/hotfix 시 마이그레이션 파일 단독 검토 상황 빈발** (파일 grep, DB 콘솔 copy-paste 등)
- **Git squash/rebase가 커밋 메시지 맥락을 잃어도 SQL 파일 코멘트는 보존**
- **`flyway_schema_history` 테이블은 "무엇이 적용됐는지"만 기록** — "왜"는 없음. SQL 코멘트가 이 gap을 메움
- **Incident 대응 시간 단축**: 새벽 3시에 `V20260419000000__create_user_role_assignments.sql`을 처음 보는 엔지니어가 "이 테이블 drop해도 되는가"를 판단할 수 있어야 함

#### 체크리스트

- [ ] 파일 첫 줄 — 파일명 echo (`-- V{timestamp}__{desc}.sql`)
- [ ] `Purpose` 1-2줄 (비즈니스 의도, 기술 스택이 아닌 요구사항 언어)
- [ ] `Source` — 상위 PDCA 문서 `§번호` 또는 이슈 ID (Jira/GitHub 키)
- [ ] `Note` — NOT NULL / DEFAULT / INDEX / CHECK 등 비자명한 판단 근거
- [ ] (테이블 DDL인 경우) `COMMENT ON TABLE`
- [ ] (테이블 DDL인 경우) `COMMENT ON COLUMN` for 비자명한 컬럼 (enum 화이트리스트, NULL 의미, 단위 등)

---

## 9. 트랜잭션 (`@Transactional`)

### 9.1 클래스/메서드 레벨

```kotlin
@Service
@Transactional       // 클래스 기본: write 트랜잭션
class UserService(...) {

    fun register(req: UserCreateRequest): UserInfoResponse {
        // 기본 @Transactional 적용 — write
    }

    @Transactional(readOnly = true)    // 메서드 override — read-only
    fun findById(id: UUID): UserInfoResponse { ... }

    @Transactional(readOnly = true)
    fun authenticate(loginId: String, rawPassword: String): User { ... }
}
```

### 9.2 `readOnly = true`의 효과

- Hibernate flush 모드 = MANUAL → dirty checking 비용 절감
- 일부 DB(특히 read replica 라우팅)에서 read-only connection으로 자동 라우팅
- **단순 조회 메서드에는 항상 명시**

### 9.3 Optimistic Locking

```kotlin
@Entity
class Stock(
    @Version
    var version: Long = 0,
    // ...
)
```

- 버전 불일치 시 `ObjectOptimisticLockingFailureException`
- `GlobalExceptionHandler.handleOptimisticLock`이 `409 CONFLICT`로 매핑

---

## 10. QueryDSL 짧은 가이드

상세 내용은 향후 `QUERYDSL_GUIDE.md` 분리 예정. 핵심만:

```kotlin
// EventQuery.kt — 표현식을 한 곳에 모음
object EventQuery {
    fun titleContains(keyword: String?) =
        keyword?.takeIf { it.isNotBlank() }?.let { event.title.contains(it) }   // null이면 .where() 자동 무시

    fun statusEq(status: EventStatus?) =
        status?.let { event.eventStatus.eq(it) }
}

// 정렬 화이트리스트 — SQL injection 방지
private val SORT_FIELDS = mapOf(
    "title" to event.title,
    "createdAt" to event.createdAt,
)
```

규칙:
- 표현식은 **`{Domain}Query` object**에 모은다
- Null-safe filter: `keyword?.takeIf { ... }?.let { ... }` → null이면 `.where()`가 자동 무시
- 정렬 필드는 **화이트리스트 Map**으로 관리

---

## 11. 트러블슈팅 / FAQ

### Q1. `InstantiationException: No default constructor for entity`

→ `kotlin("plugin.jpa")` 누락. build.gradle.kts에 추가.

### Q2. `org.hibernate.LazyInitializationException` (proxy access outside session)

→ `@Entity`가 `open`이 아님. `kotlin("plugin.allopen")` + `allOpen { annotation("jakarta.persistence.Entity") }` 추가.

### Q3. `Optional<User>` 받아 `orElseThrow` 쓰고 싶음

→ **금지**. `findByIdOrNull` + `?:` 패턴 사용 (§1).

### Q4. `findByIdOrNull` import 안 됨

→ `import org.springframework.data.repository.findByIdOrNull` (extension).

### Q5. `@JdbcTypeCode(SqlTypes.UUID)` 누락 시 어떻게 됨

→ Hibernate가 UUID를 VARCHAR로 저장 → `ddl-auto: validate` 통과 안 됨 + 인덱스 효율↓.

### Q6. `@ManyToOne` 쓰면 안 되나요?

→ **같은 Aggregate 내**에서는 OK (예: Event → EventOption). **다른 Aggregate 간**에는 ID 참조 (§6).

### Q7. JPA Auditing이 활성된 줄 알았는데 `createdAt`이 null

→ `@EnableJpaAuditing` 누락. Application 클래스 또는 별도 `@Configuration`에 명시. `requireNotNull(entity.createdAt) { "JPA Auditing must be enabled" }`로 즉시 진단.

### Q8. Flyway 마이그레이션이 실행 안 됨

→ `flyway-core`만 있고 `spring-boot-starter-flyway` 누락. Spring Boot 4의 자동 설정은 starter 필요.

### Q9. `ddl-auto: validate` 통과 안 됨

→ Flyway가 만든 스키마와 Entity 매핑 불일치. `@Column` length·nullable·unique 명시 확인. `validate`는 운영 환경 권장 — `update`나 `create`는 개발 중에도 위험.

### Q10. derive query method 이름 규칙

| Repository 메서드명 | 생성 query |
|--------------------|-----------|
| `findByLoginId(loginId: String): User?` | `WHERE login_id = ?` |
| `findByLoginIdAndPhone(...)` | `WHERE login_id = ? AND phone = ?` |
| `existsByPhone(phone: String): Boolean` | `SELECT count(*) > 0 WHERE phone = ?` |
| `countByEmail(email: String): Long` | `SELECT count(*) WHERE email = ?` |
| `deleteByLoginId(loginId: String)` | `DELETE WHERE login_id = ?` (`@Modifying` + `@Transactional` 필요) |

---

## 관련 문서

- [`TEST_WRITE_GUIDE.md`](TEST_WRITE_GUIDE.md) — `@DataJpaTest` + Testcontainers Repository 테스트
- [`DTO_WRITE_GUIDE.md`](DTO_WRITE_GUIDE.md) — Entity ↔ DTO 변환 (`from`, `toEntity`)
- `CLAUDE.md` — 프로젝트 컨벤션 맵 (Entity 4줄 컨벤션)
- `docs/01-plan/features/user.plan.md` — `findByIdOrNull` 패턴 채택 사례 (D18)
- `docs/02-design/features/user.design.md` — `User` Entity + Aggregate 경계 (Event ↔ CouponIssue) 설계
