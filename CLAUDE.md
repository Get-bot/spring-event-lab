# Spring Event Lab

선착순 쿠폰 발급 시스템 학습 프로젝트. Spring Boot 4 + Kotlin + PostgreSQL + Redis + Kafka 기반으로 고동시성 flash sale 시나리오를 다룬다.

## 작업 원칙

- **DDD 관점에서 작업한다.** Aggregate 경계, Entity 불변식, Value Object 추출, 도메인 로직의 Entity 내부 캡슐화(Rich Domain Model)를 우선한다. Anemic Domain Model(로직이 Service에 쌓이는 구조)은 지양.

## 기술 스택

- **Language**: Kotlin 2.3.20 (JDK 25)
- **Framework**: Spring Boot 4.0.5 (spring-boot-starter-webmvc, data-jpa, data-redis, kafka)
- **Persistence**: PostgreSQL 18 + Flyway + QueryDSL (openfeign fork, KSP)
- **ID 전략**: UUID v7 (`uuid-creator`의 `UuidCreator.getTimeOrderedEpoch()` + `@JdbcTypeCode(SqlTypes.UUID)`)
- **문서화**: SpringDoc OpenAPI (Swagger UI)
- **테스트**: Kotest 6.1.0 + MockK 1.14.9 + springmockk 5.0.1 + Testcontainers (→ [테스트 작성 가이드](docs/engine/TEST_WRITE_GUIDE.md))

## Knowledge Base (`docs/engine/`) — 구현 전 필독

세부 컨벤션은 모두 `docs/engine/` 가이드로 분리되어 있다. CLAUDE.md는 **맵 역할**만 한다.

> **사용 규칙**: 작업 지시·질문·설계 문서에 아래 **트리거 키워드**가 등장하면 **구현 시작 전에** 해당 가이드를 먼저 읽는다. 키워드는 대소문자 무관, 한/영 동일 취급.

| 문서 | 다루는 영역 | 트리거 키워드 |
|------|------------|-------------|
| [`JPA_WRITE_GUIDE.md`](docs/engine/JPA_WRITE_GUIDE.md) | Entity 설계 (`protected set` / `init` 불변식 / VO) · Repository (`findByIdOrNull`) · Aggregate 경계 (`@ManyToOne` 지양, ID 참조) · UUID v7 · JPA Auditing · Flyway · QueryDSL | Entity / Repository / `findByIdOrNull` / `@ManyToOne` / Aggregate / UUID v7 / `@JdbcTypeCode` / Flyway / 마이그레이션 / JPA Auditing / QueryDSL / `protected set` |
| [`DTO_WRITE_GUIDE.md`](docs/engine/DTO_WRITE_GUIDE.md) | Request/Response DTO · `toEntity()` / `from()` 패턴 · Bean Validation (`@field:` target) · SpringDoc `@Schema` · 정규식 단일 출처 · PII 마스킹 | DTO / Request / Response / `@field:` / `@Schema` / Bean Validation / `@Valid` / `@Pattern` / `toEntity` / `from(` / PII 마스킹 |
| [`TEST_WRITE_GUIDE.md`](docs/engine/TEST_WRITE_GUIDE.md) | 4-Layer Test Pyramid (L1 Domain ~ L4 Integration) · Kotest + MockK + Testcontainers · Fixture · `IntegrationTestBase` · `ProjectConfig` | 테스트 / test / Fixture / Kotest / MockK / `@MockkBean` / `@WebMvcTest` / `@DataJpaTest` / `@SpringBootTest` / Testcontainers / `withData` / `ProjectConfig` / `IntegrationTestBase` |
| [`ERROR_WRITE_GUIDE.md`](docs/engine/ERROR_WRITE_GUIDE.md) | ErrorCode 명명·의사결정 · `BusinessException` 사용 · `GlobalExceptionHandler` 확장 · `ErrorResponse` 구조 · 로깅 레벨 정책 (4xx=warn / 5xx=error) · cause chain unwrapping | ErrorCode / `BusinessException` / `GlobalExceptionHandler` / `@RestControllerAdvice` / `@ExceptionHandler` / ErrorResponse / 에러 코드 / 예외 / 에러 핸들러 / 로깅 레벨 / cause chain / unwrap |

## 컨벤션 (engine/에 없는 고유 항목)

### ErrorCode
- 상세: [`ERROR_WRITE_GUIDE.md §1`](docs/engine/ERROR_WRITE_GUIDE.md#1-errorcode-명명의사결정) 참조

### Pagination
- `@PageableDefault` + `@ParameterObject` 사용 (커스텀 Pageable 지양)
- 1-based 페이지: `spring.data.web.pageable.one-indexed-parameters=true`

## 컨벤션

### Entity
- 주 생성자 파라미터 + `protected set` var 필드 (JPA 호환 + 캡슐화)
- 도메인 불변식은 `init` 블록 또는 Value Object에서 검증
- 상태 전이 메서드(`open()`, `close()`, `issue()`)는 Entity 내부에 둔다
- `kotlin("plugin.jpa")` 활용 → no-arg constructor 자동 생성

### DTO
- `EventCreateRequest.toEntity()` — DTO가 자신의 변환을 책임
- `EventResponse.from(entity)` — 응답 DTO의 companion object 팩토리
- Validation: `@field:` target 명시 (`@field:NotBlank`, `@field:Min` 등)

### ErrorCode
- 패턴: `{DOMAIN}_{CONDITION}` (예: `EVENT_NOT_FOUND`, `EVENT_NOT_OPEN`)
- 공통 에러는 prefix 없음 (`INVALID_INPUT`, `UNAUTHORIZED`)
- 같은 HTTP 상태에서 원인별 분리 시 `E409-1`, `E409-2` 식으로 서브코드

### QueryDSL
- 표현식은 `EventQuery` object에 모음 (where / order 함수들)
- Null-safe 필터 패턴: `keyword?.takeIf { ... }?.let { ... }` → null이면 `.where()`가 자동 무시
- 정렬 필드는 **화이트리스트 Map**으로 관리 (SQL injection 방지)

### Pagination
- `@PageableDefault` + `@ParameterObject` 사용 (커스텀 Pageable 지양)
- 1-based 페이지: `spring.data.web.pageable.one-indexed-parameters=true`

## 주요 설계 결정

### Aggregate 분리 — Event ↔ CouponIssue
- **`@ManyToOne` 사용하지 않음**. `CouponIssue.eventId: UUID`로 ID 참조만.
- 이유: DDD Aggregate 경계 준수 + 선착순 환경 성능 최적화 (N+1 방지)
- 조회 편의가 필요하면 Service에서 명시적 조합

### Value Object — DateRange
- `global/common/DateRange.kt` (`@Embeddable`)
- `startedAt < endedAt` 불변식을 DateRange가 책임
- 향후 Coupon 유효기간 등에서 재사용 예정

## 개발 워크플로우

### 로컬 실행 (앱만 로컬, 인프라는 Docker)
```bash
docker compose up -d postgres redis kafka
./gradlew bootRun
```

### 전부 Docker
```bash
docker compose up
```

### 접속
- API: `http://localhost:8080`
- Swagger UI: `http://localhost:8080/swagger-ui.html`
- PostgreSQL: `localhost:5432/eventlab`

### 마이그레이션
- `src/main/resources/db/migration/V{YYYYMMDDHHMMSS}__{description}.sql`
- Flyway가 앱 부팅 시 자동 실행 (`spring-boot-starter-flyway`)
- `ddl-auto: validate`로 Entity↔스키마 불일치를 부팅 시 감지

## Knowledge Base (`docs/engine/`)

상세 가이드는 아래 문서 참조. CLAUDE.md는 맵 역할만 하며, 깊은 정보는 docs/engine/에 둔다.

| 문서 | 내용 |
|------|------|
| [`TEST_WRITE_GUIDE.md`](docs/engine/TEST_WRITE_GUIDE.md) | 테스트 작성 종합 가이드 — 4계층 Test Pyramid, 계층별 패턴, Fixture, 트러블슈팅 |

## 알려진 주의사항

- **Spring Boot 4 Flyway**: `flyway-core`만으로는 auto-config이 안 됨. 반드시 `spring-boot-starter-flyway` 사용
- **Kotlin `@field:` target**: Bean Validation 어노테이션은 `@field:` prefix 필수 (생성자 프로퍼티 모호성 해결)
- **QueryDSL KSP**: Entity 변경 시 KSP가 QEvent 등을 재생성. 간혹 `./gradlew clean kspKotlin` 필요
