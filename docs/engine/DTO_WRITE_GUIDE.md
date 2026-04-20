# DTO Writing Guide — BuWs API Server

> **Tech Stack**: Spring Boot 4.0.5 / Kotlin 2.3.20 / Bean Validation (jakarta.validation) / SpringDoc OpenAPI
> **Origin**: `EventCreateRequest` / `EventResponse` 컨벤션 + `user` PDCA(2026-04) 패턴 정리
> **Last Updated**: 2026-04-17

본 문서는 BuWs API Server의 **Request / Response / Command DTO 표준 패턴**을 정리합니다. CLAUDE.md의 DTO 3줄 컨벤션을 풀어쓴 deep-dive 가이드입니다.

---

## 목차

1. [DTO 책임 분리 — 4 Type](#1-dto-책임-분리--4-type)
2. [Request DTO 표준 패턴](#2-request-dto-표준-패턴)
3. [Response DTO 표준 패턴](#3-response-dto-표준-패턴)
4. [Bean Validation 컨벤션](#4-bean-validation-컨벤션)
5. [SpringDoc OpenAPI (`@Schema`)](#5-springdoc-openapi-schema)
6. [Nullable Wrap + `!!` Unpack](#6-nullable-wrap--unpack)
7. [`toEntity()` 위임 — 표준 + 변형](#7-toentity-위임--표준--변형)
8. [정규식 단일 출처 (`UserPattern` 같은 object)](#8-정규식-단일-출처-userpattern-같은-object)
9. [DTO 위치 컨벤션](#9-dto-위치-컨벤션)
10. [트러블슈팅 / FAQ](#10-트러블슈팅--faq)

---

## 1. DTO 책임 분리 — 4 Type

| Type | 위치 | 책임 | 변환 메서드 |
|------|------|------|-------------|
| **Request** | `{feature}/dto/request/` | HTTP 입력 (Controller `@RequestBody`) | `toEntity()` 또는 변형 (`toUser(encodedPassword)`) |
| **Response** | `{feature}/dto/response/` | HTTP 출력 (Controller `ResponseEntity<T>`) | `companion object { fun from(entity): T }` |
| **Command** *(선택)* | `{feature}/dto/command/` | Service 입력 (HTTP 노출 없음, Service 간 호출) | `toEntity()` 또는 Service에서 destructuring |
| **Query** *(선택)* | `{feature}/dto/query/` | 검색 조건 (`@ParameterObject` 등) | QueryDSL Predicate 변환 |

**원칙**:
- DTO는 **자기 변환을 자기가 책임진다** (Service에 변환 로직 누적 방지 — Anemic 안티패턴 회피)
- DTO는 **자기 검증을 자기가 책임진다** (Bean Validation으로 fail-fast)
- DTO는 **외부 Bean 의존 0개** — `PasswordEncoder`, `Repository` 같은 Spring Bean을 DTO가 들지 않는다 (예외 처리는 §7)

---

## 2. Request DTO 표준 패턴

`EventCreateRequest`는 본 프로젝트 Request DTO의 정식 reference입니다.

```kotlin
@Schema(description = "이벤트 생성 요청")
data class EventCreateRequest(
    @field:NotBlank(message = "이벤트 제목은 필수입니다")
    @field:Size(max = 200, message = "이벤트 제목은 200자 이하여야 합니다")
    @Schema(description = "이벤트 제목", example = "2026년 여름 쿠폰 이벤트", requiredMode = REQUIRED)
    val title: String,

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
    fun toEntity(): Event =
        Event(
            title = title,
            totalQuantity = totalQuantity!!,
            eventStatus = EventStatus.READY,
            period = DateRange(startedAt!!, endedAt!!),
        )
}
```

### 패턴 체크리스트

- [ ] **`data class`** — `equals`/`hashCode`/`copy`/`toString` 자동 생성
- [ ] **클래스 레벨 `@Schema(description = "...")`** — Swagger 그룹 헤더
- [ ] **각 필드 `@field:Validation`** — `@field:` prefix 필수 (§4 참조)
- [ ] **각 필드 `@Schema(description, example, requiredMode = REQUIRED)`** — Swagger 필드 문서화
- [ ] **숫자/일시는 nullable** (`Int?`, `Instant?`) — `@field:NotNull`로 검증, `toEntity()`에서 `!!` unpack (§6 참조)
- [ ] **문자열은 non-null** (`String`) — `@field:NotBlank`로 빈 문자열까지 차단
- [ ] **`fun toEntity(): Entity`** — DTO 자체 책임. Service는 호출만 한다

### Controller 사용 예

```kotlin
@PostMapping
fun create(@Valid @RequestBody req: EventCreateRequest): ResponseEntity<EventResponse> {
    val event = eventService.create(req.toEntity())
    return ResponseEntity.created(URI.create("/api/v1/events/${event.id}"))
        .body(EventResponse.from(event))
}
```

---

## 3. Response DTO 표준 패턴

```kotlin
data class EventResponse(
    val id: UUID,
    val title: String,
    val totalQuantity: Int,
    val status: EventStatus,
    val startedAt: Instant,
    val endedAt: Instant,
    val createdAt: Instant,
) {
    companion object {
        fun from(event: Event): EventResponse = EventResponse(
            id = event.id,
            title = event.title,
            totalQuantity = event.totalQuantity,
            status = event.eventStatus,
            startedAt = event.period.startedAt,
            endedAt = event.period.endedAt,
            createdAt = event.createdAt,
        )
    }
}
```

### 패턴 체크리스트

- [ ] **`data class`**
- [ ] **`companion object { fun from(entity): T }`** — Entity → DTO 변환은 Response의 책임
- [ ] **PII 필드는 마스킹** (`PiiMask.phone(...)`, `PiiMask.email(...)` — `user` 도메인 참조)
- [ ] **모든 필드 non-null** — Entity가 영속화된 시점에는 모든 값이 채워져 있어야 한다 (예외: `BaseTimeEntity.createdAt`이 nullable인 경우 `requireNotNull` 또는 nullable 응답 결정)
- [ ] **`@Schema` 출력 응답에는 일반적으로 불필요** — SpringDoc은 type 정보로 자동 추론. 필요시 클래스 레벨 description만

---

## 4. Bean Validation 컨벤션

### 4.1 `@field:` prefix는 필수

Kotlin 생성자 프로퍼티에 어노테이션을 붙일 때, JVM 입장에서 어노테이션 대상이 모호합니다 (constructor parameter / field / getter / setter). Bean Validation은 **field**에 적용되므로 명시적으로 `@field:` target을 지정해야 합니다.

```kotlin
// ❌ 작동하지 않음 — 어노테이션이 constructor parameter에만 붙음
data class Req(@NotBlank val name: String)

// ✅ 올바름
data class Req(@field:NotBlank val name: String)
```

### 4.2 자주 쓰는 어노테이션 카탈로그

| 어노테이션 | 적용 대상 | 의미 |
|------------|-----------|------|
| `@field:NotBlank` | `String` non-null | 빈 문자열·공백 차단 |
| `@field:NotNull` | nullable 타입 | null 차단 (검증 후 `!!` unpack) |
| `@field:NotEmpty` | `Collection`, `Array` | 비어있지 않음 |
| `@field:Size(min, max)` | `String`, `Collection` | 길이 범위 |
| `@field:Min(value)` / `@field:Max(value)` | `Int`, `Long` | 숫자 범위 |
| `@field:Pattern(regexp = "...")` | `String` | 정규식 매칭. 정규식은 **컴파일 타임 `String const`만** 받음 (§8 참조) |
| `@field:Email` | `String` | RFC 5322 부분 준수 (느슨함 — 엄격 검증은 `@field:Pattern` 권장) |
| `@field:Valid` | nested DTO | 중첩 객체도 재귀 검증 |
| `@field:Past` / `@field:Future` | `Instant`, `LocalDateTime` | 시점 비교 |

### 4.3 `message` 인자는 한국어 + 사용자 친화적

`GlobalExceptionHandler.handleValidation`이 `BindingResult.fieldErrors`를 그대로 응답에 노출하므로, `message`는 그대로 사용자에게 표시됩니다.

```kotlin
@field:NotBlank(message = "이벤트 제목은 필수입니다")  // ✅
@field:NotBlank(message = "title must not be blank") // ❌ — 사용자 노출되는 영문 디버그 메시지
```

---

## 5. SpringDoc OpenAPI (`@Schema`)

SpringDoc OpenAPI는 `org.springdoc:springdoc-openapi-starter-webmvc-ui`를 통해 Swagger UI를 자동 생성합니다. `@Schema` 어노테이션은 **두 위치**에 사용합니다.

### 5.1 클래스 레벨 (DTO 그룹 헤더)

```kotlin
@Schema(description = "이벤트 생성 요청")
data class EventCreateRequest(...)
```

### 5.2 필드 레벨

```kotlin
@Schema(
    description = "이벤트 제목",                      // 필드 설명
    example = "2026년 여름 쿠폰 이벤트",              // Swagger UI "Example Value"
    requiredMode = Schema.RequiredMode.REQUIRED,      // OpenAPI required: true
)
val title: String,
```

| 인자 | 의미 |
|------|------|
| `description` | Swagger UI 필드 설명. 필수 |
| `example` | Swagger UI "Try it out" 기본 값. 필수 |
| `requiredMode = REQUIRED` | OpenAPI 스펙 `required: true`. `@field:NotNull`/`@field:NotBlank`와 함께 항상 명시 |
| `defaultValue` | 옵셔널 필드 기본값 |
| `accessMode = READ_ONLY` | Response 전용 필드 (Request에 등장 시 무시) |

### 5.3 Enum 필드

```kotlin
@Schema(description = "이벤트 상태", example = "READY", implementation = EventStatus::class)
val status: EventStatus
```

`InvalidFormatException` 발생 시 `GlobalExceptionHandler.handleNotReadable`이 enum 후보 값을 자동 추출해 응답에 포함합니다.

---

## 6. Nullable Wrap + `!!` Unpack

### 6.1 왜 nullable로 받는가

Bean Validation `@NotNull`은 **null이 도달했을 때 검증 에러로 변환**해야 합니다. 만약 필드를 non-null로 선언하면, Jackson이 deserialize 단계에서 먼저 NPE를 던져서 `MethodArgumentNotValidException`이 아닌 `HttpMessageNotReadableException`이 발생합니다. → 표준 400 응답이 아닌 "JSON parse error"가 노출됨.

```kotlin
// ❌ — null 입력 시 Jackson 단계에서 NPE → "Missing or invalid field"
val totalQuantity: Int

// ✅ — Bean Validation 단계에서 catch → "총 수량은 필수입니다"
@field:NotNull(message = "총 수량은 필수입니다")
val totalQuantity: Int?
```

### 6.2 `toEntity()`에서 `!!` Unpack

`@Valid` 통과 시 nullable이지만 실제로는 non-null이 보장됨 → `!!` 안전.

```kotlin
fun toEntity(): Event = Event(
    title = title,                          // String non-null
    totalQuantity = totalQuantity!!,        // Int? → Int (검증 후 안전)
    period = DateRange(startedAt!!, endedAt!!),
)
```

### 6.3 `String`은 non-null로

`@NotBlank`는 빈 문자열·공백·null 모두 차단합니다. 굳이 nullable로 둘 필요 없음.

```kotlin
@field:NotBlank val title: String   // ✅
@field:NotBlank val title: String?  // ❌ — toEntity에서 불필요한 !! 추가
```

---

## 7. `toEntity()` 위임 — 표준 + 변형

### 7.1 Self-contained (표준)

`EventCreateRequest`처럼 외부 Bean 의존이 없는 경우, DTO 안에서 Entity를 그대로 생성합니다.

### 7.2 외부 의존이 있는 경우 — `toUser(encodedPassword)` 변형

`UserCreateRequest`는 비밀번호를 BCrypt 인코딩해야 하므로 `PasswordEncoder` Bean이 필요합니다. DTO는 Bean을 들 수 없으므로 **인코딩만 Service가 처리하고 나머지 변환은 DTO에 위임**합니다.

```kotlin
// user/dto/request/UserCreateRequest.kt
data class UserCreateRequest(
    @field:NotBlank @field:Pattern(regexp = UserPattern.LOGIN_ID_REGEX)
    val loginId: String,
    @field:NotBlank @field:Pattern(regexp = UserPattern.PHONE_REGEX)
    val phone: String,
    // ...
    @field:NotBlank @field:Size(min = 8, max = 100)
    val password: String,   // 평문
    // ...
) {
    fun toUser(encodedPassword: String): User =
        User(loginId = loginId, phone = phone, /* ... */, encodedPassword = encodedPassword, /* ... */)
}

// user/service/UserService.kt
fun register(req: UserCreateRequest): UUID {
    // ... 중복 검증
    val user = req.toUser(encodedPassword = passwordEncoder.encode(req.password))
    return userRepository.save(user).id
}
```

**원칙**:
- DTO 메서드 이름은 `toEntity` 대신 **대상 Entity 이름 + 외부 의존 명시** (`toUser(encodedPassword)`)
- DTO는 평문 비밀번호를 보유하지만 **`toString()`/로그 노출 금지** — `data class`의 자동 `toString()`은 평문을 노출하므로 필요시 override

---

## 8. 정규식 단일 출처 (`UserPattern` 같은 object)

`@field:Pattern(regexp = ...)`은 **컴파일 타임 `String const`만** 받습니다. `val Regex`는 사용 불가. 따라서 **String const + Regex 쌍**을 한 곳에 두어 DTO와 Entity가 공유하는 패턴을 권장합니다.

```kotlin
// user/UserPattern.kt
object UserPattern {
    const val LOGIN_ID_REGEX = "^[A-Za-z0-9_-]{4,30}$"
    const val PHONE_REGEX    = "^\\+?[0-9]{10,15}$"
    const val EMAIL_REGEX    = "^[A-Za-z0-9+_.-]+@[A-Za-z0-9.-]+\\.[A-Za-z]{2,}$"

    val LOGIN_ID: Regex = Regex(LOGIN_ID_REGEX)   // 1회 컴파일 후 재사용
    val PHONE:    Regex = Regex(PHONE_REGEX)
    val EMAIL:    Regex = Regex(EMAIL_REGEX)
}

// DTO 사용
@field:Pattern(regexp = UserPattern.LOGIN_ID_REGEX)
val loginId: String,

// Entity init 사용
init {
    require(UserPattern.LOGIN_ID.matches(loginId)) { "invalid loginId format" }
}
```

**효과**:
- DTO/Entity가 같은 패턴 문자열을 공유 → 한쪽만 수정해도 양쪽 일관성 유지 (DRY)
- DTO에서는 컴파일 타임 String 사용, Entity init에서는 사전 컴파일된 Regex 객체 사용 → 매번 정규식 재컴파일 회피

---

## 9. DTO 위치 컨벤션

```
{feature}/
├── dto/
│   ├── request/
│   │   └── {Feature}CreateRequest.kt    # POST 입력
│   │   └── {Feature}UpdateRequest.kt    # PATCH/PUT 입력
│   │   └── {Feature}SearchCond.kt       # GET ?query=... 검색 조건 (@ParameterObject)
│   └── response/
│       └── {Feature}Response.kt         # 단건 조회 응답
│       └── {Feature}ListResponse.kt     # 목록 응답 (페이징 등)
└── ...
```

**규칙**:
- Request와 Response는 같은 `dto/` 하위에서 **별도 디렉터리로 분리**
- 단일 책임 원칙: DTO 클래스는 한 endpoint의 한 방향(in 또는 out)만 담당
- 같은 필드 집합이라도 Request와 Response를 같은 클래스로 공유하지 않음 (Anti-pattern: `EventDto` 한 개로 양방향 사용)

---

## 10. 트러블슈팅 / FAQ

### Q1. `@NotBlank`가 동작하지 않습니다

→ `@field:` prefix 누락 가능성. `@field:NotBlank`로 수정.

### Q2. Swagger UI에 필드가 빨간 별표(*)로 안 나옵니다

→ `@Schema(requiredMode = REQUIRED)` 누락. `@field:NotNull`만으로는 OpenAPI required 표시 안 됨.

### Q3. `@field:Pattern(regexp = MyClass.PATTERN)`이 컴파일 에러

→ `MyClass.PATTERN`이 `const val`이 아니라 `val`. `const val`로 변경 필요. `object` 또는 `companion object` 안에서만 `const val` 가능.

### Q4. `toEntity()`가 `!!`로 너무 더럽습니다

→ 검증 통과 후라는 단서를 메서드 docstring으로 명시. Kotlin 관용구. 단, `!!` 호출이 5개 이상이면 `requireNotNull` + 명확한 에러 메시지 사용 고려.

### Q5. Entity가 nullable 필드를 가질 때 Response DTO는?

→ `BaseTimeEntity.createdAt: Instant?` 같은 경우. 영속화된 Entity는 항상 값이 있음을 가정하므로:
```kotlin
createdAt = requireNotNull(entity.createdAt) { "createdAt must be set by JPA Auditing" }
```
NPE 시 즉시 "Auditing 미활성"이 진단됨. `@EnableJpaAuditing` 활성 확인 필수.

### Q6. Service가 DTO를 직접 받아도 되나요? (Layer 침투 우려)

→ 본 프로젝트 컨벤션은 **`UserService.register(req: UserCreateRequest)`처럼 Service가 Request DTO를 직접 받는 것을 허용**합니다. 이유:
- DTO를 다시 풀어 raw 파라미터로 변환하는 보일러플레이트 제거
- DTO `toEntity()` 책임을 활용
- 실제로 모든 Service 호출자가 HTTP Controller인 경우가 일반적

단, **Service 메서드가 다중 채널에서 호출**되거나 **DTO가 HTTP-specific 어노테이션(`@Schema` 등) 외에 더 무거운 책임을 가질 가능성**이 있다면, Command 패턴(별도 `dto/command/UserRegisterCommand`)으로 분리합니다. (`user` feature에서는 단일 채널이라 Request DTO 직접 사용)

---

## 관련 문서

- [`TEST_WRITE_GUIDE.md`](TEST_WRITE_GUIDE.md) — DTO 검증 테스트 (L3 Slice / `@WebMvcTest`)
- `CLAUDE.md` — 프로젝트 컨벤션 맵
- `docs/01-plan/features/user.plan.md` — `UserCreateRequest` 정의 사례
- `docs/02-design/features/user.design.md` — `UserPattern` + DTO 통합 설계
