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
- **테스트**: JUnit5 + Testcontainers

## 프로젝트 구조

```
src/main/kotlin/com/beomjin/springeventlab/
├── coupon/                    # 쿠폰/이벤트 도메인
│   ├── controller/            # REST 엔드포인트
│   ├── dto/
│   │   ├── request/           # 요청 DTO + Bean Validation
│   │   └── response/          # 응답 DTO (from() 팩토리)
│   ├── entity/                # JPA Entity + 도메인 로직
│   ├── repository/            # JpaRepository + QueryDSL
│   └── service/
│       ├── EventQuery.kt      # QueryDSL 표현식 모음 (where/order)
│       └── EventService.kt    # 유스케이스 orchestration
└── global/
    ├── common/                # BaseTimeEntity, DateRange, PageResponse 등 공통
    ├── config/                # JpaConfig (JPAQueryFactory), SwaggerConfig
    └── exception/             # BusinessException, ErrorCode, GlobalExceptionHandler
```

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

## 알려진 주의사항

- **Spring Boot 4 Flyway**: `flyway-core`만으로는 auto-config이 안 됨. 반드시 `spring-boot-starter-flyway` 사용
- **Kotlin `@field:` target**: Bean Validation 어노테이션은 `@field:` prefix 필수 (생성자 프로퍼티 모호성 해결)
- **QueryDSL KSP**: Entity 변경 시 KSP가 QEvent 등을 재생성. 간혹 `./gradlew clean kspKotlin` 필요
