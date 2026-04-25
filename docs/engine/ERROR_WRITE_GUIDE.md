# Error Writing Guide — BuWs API Server

> **Tech Stack**: Spring Boot 4.0.5 / Kotlin 2.3.20 / `@RestControllerAdvice` / `io.github.oshai:kotlin-logging`
> **Origin**: `GlobalExceptionHandler` + `ErrorCode` enum + `user` PDCA(2026-04) 패턴 정리
> **Last Updated**: 2026-04-17

본 문서는 BuWs API Server의 **에러 설계·발생·핸들링·로깅 표준 패턴**을 정리합니다. CLAUDE.md의 ErrorCode 3줄 컨벤션을 풀어쓴 deep-dive 가이드입니다.

---

## 목차

1. [ErrorCode 명명·의사결정](#1-errorcode-명명의사결정)
2. [`BusinessException` 사용](#2-businessexception-사용)
3. [`GlobalExceptionHandler` 확장 패턴](#3-globalexceptionhandler-확장-패턴)
4. [`ErrorResponse` 구조](#4-errorresponse-구조)
5. [로깅 레벨 정책](#5-로깅-레벨-정책)
6. [Cause Chain Unwrapping](#6-cause-chain-unwrapping)
7. [체크리스트 / 트러블슈팅 / FAQ](#7-체크리스트--트러블슈팅--faq)

---

## 1. ErrorCode 명명·의사결정

`ErrorCode`는 `httpStatus` · `code` · `message` 3개 필드를 가진 enum. **"한 상황 = 하나의 ErrorCode"** 원칙으로 설계합니다.

```kotlin
// global/exception/ErrorCode.kt
enum class ErrorCode(
    val httpStatus: HttpStatus,
    val code: String,
    val message: String,
) {
    // Common Errors (prefix 없음)
    INVALID_INPUT(HttpStatus.BAD_REQUEST, "C400", "잘못된 입력입니다."),
    UNAUTHORIZED(HttpStatus.UNAUTHORIZED, "C401", "인증에 실패했습니다."),
    // ...

    // User Errors (도메인 prefix)
    USER_LOGIN_ID_DUPLICATE(HttpStatus.CONFLICT, "U409-1", "이미 사용 중인 로그인 ID입니다."),
    USER_CREDENTIAL_INVALID(HttpStatus.UNAUTHORIZED, "U401", "로그인 ID 또는 비밀번호가 올바르지 않습니다."),
    USER_NOT_FOUND(HttpStatus.NOT_FOUND, "U404", "사용자를 찾을 수 없습니다."),
}
```

### 1.1 Enum 이름 — `{DOMAIN}_{CONDITION}`

| 구분 | Enum 이름 규칙 | 예시 |
|------|----------------|------|
| **공통 에러** | prefix 없음 (`{CONDITION}`) | `INVALID_INPUT`, `UNAUTHORIZED`, `FORBIDDEN`, `NOT_FOUND`, `CONFLICT` |
| **도메인 에러** | `{DOMAIN}_{CONDITION}` | `USER_NOT_FOUND`, `USER_LOGIN_ID_DUPLICATE`, `EVENT_NOT_OPEN` |
| **인프라 에러** | `{INFRA}_{CONDITION}` | `REDIS_UNAVAILABLE` |

**원칙**:
- `CONDITION`은 **상태/결과**로 표현: `NOT_FOUND`, `DUPLICATE`, `INVALID`, `UNAVAILABLE`, `NOT_OPEN`
- ❌ 동사형 피하기: `USER_FAIL_TO_FIND` (X) → `USER_NOT_FOUND` (O)
- ❌ 파라미터 이름 넣지 않기: `USER_LOGIN_ID_LENGTH_INVALID` (X) — 그건 Bean Validation의 몫. `ErrorCode`는 "**비즈니스 의미**"만

### 1.2 `code` 문자열 — `{DomainPrefix}{HttpStatus}[-{SubCode}]`

| 구성 | 규칙 | 예시 |
|------|------|------|
| **Domain Prefix** | 공통=`C`, 도메인 이니셜 1자=`U`(User), `E`(Event), `R`(Redis, 인프라) | `C400`, `U404`, `E409`, `R503` |
| **HttpStatus** | 3자리 HTTP 상태 코드 | `400`, `401`, `409`, `500` |
| **SubCode** | 같은 `{Prefix}{Status}` 내 원인 분리 시 `-1`, `-2`, ... | `U409-1`, `U409-2`, `U409-3` |

**서브코드 판단 기준**: 같은 HTTP 상태에서 클라이언트가 **분기 처리가 필요한가?** — Yes면 서브코드, No면 하나의 코드로 통합.

```kotlin
// ✅ 클라이언트가 "어떤 필드가 중복인지" 화면에 표시해야 함 → 분리
USER_LOGIN_ID_DUPLICATE(HttpStatus.CONFLICT, "U409-1", "이미 사용 중인 로그인 ID입니다."),
USER_PHONE_DUPLICATE(HttpStatus.CONFLICT, "U409-2", "이미 등록된 휴대폰 번호입니다."),
USER_EMAIL_DUPLICATE(HttpStatus.CONFLICT, "U409-3", "이미 등록된 이메일입니다."),

// ❌ 클라이언트가 분기할 이유 없음 → 하나로 합치는 게 맞음
USER_CREDENTIAL_INVALID_LOGIN_ID(..., "U401-1", ...),  // "ID 틀림"
USER_CREDENTIAL_INVALID_PASSWORD(...,  "U401-2", ...)  // "비번 틀림"
// → Credential Stuffing 방어 측면에서도 합쳐야 함
```

### 1.3 `message` — 한국어, 사용자 친화적

`ErrorCode.message`는 **그대로 사용자에게 표시**됩니다 (`ErrorResponse.message` 필드).

```kotlin
// ✅ 사용자 관점 한국어, 다음 행동이 명확
"이미 사용 중인 로그인 ID입니다."
"로그인 ID 또는 비밀번호가 올바르지 않습니다."
"요청이 너무 많습니다. 잠시 후 다시 시도해주세요."

// ❌ 디버그 메시지 (영문/기술용어)
"User with loginId already exists in DB"
"Authentication failed due to password mismatch"
```

**디버그 컨텍스트는 `BusinessException(errorCode, "debug context")` 두 번째 인자로** (§2.2 참조).

### 1.4 ErrorCode 추가 결정 플로우

```
새 에러 상황 발생
  │
  ├─ 기존 ErrorCode로 충분한가? ──── Yes ──→ 재사용
  │                                          (예: 단순 "NOT_FOUND"는 공통 사용)
  │  No
  │
  ├─ 도메인 특화 메시지가 필요한가? ─── Yes ──→ 도메인 prefix ErrorCode 추가
  │                                              (예: USER_NOT_FOUND "사용자를 찾을 수 없습니다")
  │  No
  │
  └─ HTTP 상태가 일반 패턴과 다른가? ── Yes ──→ 공통 ErrorCode 추가
                                                 (예: TOO_MANY_REQUESTS, SERVICE_UNAVAILABLE)
```

---

## 2. `BusinessException` 사용

모든 비즈니스 예외는 `BusinessException(errorCode, [debugMessage])`로 통일합니다. `RuntimeException`을 직접 던지거나 커스텀 예외 계층을 만들지 않습니다.

```kotlin
// global/exception/BusinessException.kt
open class BusinessException(
    val errorCode: ErrorCode,
    override val message: String = errorCode.message,
) : RuntimeException(message)
```

### 2.1 기본 사용 — `throw BusinessException(ErrorCode.X)`

```kotlin
// Service에서 — findByIdOrNull + elvis
fun findById(id: UUID): UserInfoResponse {
    val user = userRepository.findByIdOrNull(id)
        ?: throw BusinessException(ErrorCode.USER_NOT_FOUND, "id=$id")
    return UserInfoResponse.from(user)
}

// Service에서 — 상태 검증
if (userRepository.existsByPhone(req.phone)) {
    throw BusinessException(ErrorCode.USER_PHONE_DUPLICATE)
}
```

### 2.2 디버그 컨텍스트 override — `BusinessException(errorCode, "context")`

**사용자 응답에는 `errorCode.message`가 가지만, 로그에는 두 번째 인자 문자열이 찍힙니다.** 디버깅에 필요한 값(ID, 입력값 등)을 로그에만 남길 때 사용.

```kotlin
// ✅ 로그에 어떤 loginId에서 충돌났는지 남음. 사용자에겐 표준 한국어 메시지만 노출.
throw BusinessException(ErrorCode.USER_LOGIN_ID_DUPLICATE, "loginId=${req.loginId}")
// 로그:   "Business exception: code=U409-1, message=loginId=johndoe"
// 응답:   { "code": "U409-1", "message": "이미 사용 중인 로그인 ID입니다." }
```

`GlobalExceptionHandler.handleBusiness`는 **응답 생성 시 `ErrorResponse.of(e.errorCode)`를 호출하므로 `e.message`가 아닌 `errorCode.message`를 쓴다**. 따라서 디버그 문자열이 사용자에게 새지 않음.

### 2.3 발생 위치 — Service / Entity / VO / Controller

| 위치 | 허용 | 예 |
|------|------|-----|
| **Service** | ✅ 표준 | 중복 검증, 리소스 조회 실패, 상태 충돌 |
| **Entity `init`** | ✅ 불변식 | `require(...)` 대체 (도메인 예외) |
| **Value Object `init`** | ✅ 불변식 | `DateRange`: `startedAt < endedAt` |
| **Controller** | ⚠️ 제한적 | `@AuthenticationPrincipal` null 가드 등 최소한만 |
| **Repository** | ❌ 금지 | Repository는 Optional/nullable만 반환. 판정은 상위 계층이 |
| **DTO** | ❌ 금지 | Bean Validation으로 해결 (§DTO_WRITE_GUIDE) |

```kotlin
// ✅ Value Object — 불변식은 VO가 책임
class DateRange(startedAt: Instant, endedAt: Instant) {
    init {
        if (!startedAt.isBefore(endedAt)) {
            throw BusinessException(
                ErrorCode.INVALID_DATE_RANGE,
                "startedAt=$startedAt, endedAt=$endedAt",
            )
        }
    }
}

// ✅ Controller — 최소한의 인증 가드
fun me(@AuthenticationPrincipal userId: UUID?): ResponseEntity<UserInfoResponse> =
    ResponseEntity.ok(userService.findById(userId ?: throw BusinessException(ErrorCode.UNAUTHORIZED)))
```

### 2.4 `BusinessException` 상속 금지

`open class`로 선언되어 있지만, **하위 예외 클래스를 새로 만들지 않습니다**. 이유:

- `GlobalExceptionHandler.handleBusiness`가 `ErrorCode` 1개만 보고 응답을 구성 → 하위 타입 분기 불필요
- ErrorCode로 충분히 구분되므로 타입 계층은 중복
- Kotest `shouldThrow<BusinessException> { ... }.errorCode shouldBe ErrorCode.X` 패턴이 모든 케이스를 커버

예외적으로 `@ControllerAdvice`에서 특별히 잡아야 하는 경우(e.g., Retry 대상 예외)에 한해 상속 허용하되, **먼저 ErrorCode만으로 해결 가능한지 검토**.

---

## 3. `GlobalExceptionHandler` 확장 패턴

모든 예외 변환은 `@RestControllerAdvice class GlobalExceptionHandler`에 집중됩니다. Controller에서 `try/catch`로 예외를 잡지 않습니다 (fail-fast + 관심사 분리).

### 3.1 현재 등록된 핸들러 카탈로그

| 핸들러 | 대상 예외 | HTTP | ErrorCode | 로깅 |
|--------|-----------|------|-----------|------|
| `handleBusiness` | `BusinessException` | `e.errorCode.httpStatus` | `e.errorCode` | `warn` |
| `handleValidation` | `BindException`, `MethodArgumentNotValidException` | 400 | `INVALID_INPUT` + `fieldErrors` | `warn` |
| `handleTypeMismatch` | `MethodArgumentTypeMismatchException` | 400 | `INVALID_INPUT` | `warn` |
| `handleNotReadable` | `HttpMessageNotReadableException` | 400 (또는 cause의 httpStatus) | `INVALID_INPUT` 또는 cause의 ErrorCode | `warn` |
| `handleOptimisticLock` | `ObjectOptimisticLockingFailureException` | 409 | `CONFLICT` | `warn` |
| `handleDataIntegrity` | `DataIntegrityViolationException` | 409 | `CONFLICT` | `warn` |
| `handleNoResourceFound` | `NoResourceFoundException` | 404 | `NOT_FOUND` | `warn` |
| `handleRedisUnavailable` | `RedisConnectionFailureException`, `RedisSystemException` | 503 | `REDIS_UNAVAILABLE` | `error(e)` |
| `handleUnexpected` | `Exception` (catch-all) | 500 | `INTERNAL_SERVER_ERROR` | `error(e)` |

### 3.2 새 핸들러 추가 결정 플로우

```
새 예외 상황 발생
  │
  ├─ Service에서 제어 가능한 비즈니스 예외? ── Yes ──→ BusinessException + ErrorCode 추가
  │                                                    (핸들러 추가 불필요)
  │  No (프레임워크/라이브러리/인프라 예외)
  │
  ├─ 기존 핸들러로 처리되는가?
  │  (catch-all handleUnexpected가 500으로 잡음) ──── Yes, 500 응답이 적절 ──→ 현상 유지
  │  │
  │  No (400/409 등 특수 처리 필요)
  │
  ├─ HTTP 상태·ErrorCode가 기존과 다른가? ── Yes ──→ 신규 핸들러 추가
  │                                                  + 필요시 ErrorCode 추가
  │
  └─ cause chain으로 BusinessException을 꺼낼 필요가 있는가? ── Yes ──→ handleNotReadable처럼 unwrap (§6)
```

### 3.3 새 핸들러 템플릿

```kotlin
@ExceptionHandler(SomeFrameworkException::class)
fun handleSomething(e: SomeFrameworkException): ResponseEntity<ErrorResponse> {
    log.warn { "Something happened: ${e.message}" }   // 4xx면 warn, 5xx면 error(e)
    return ResponseEntity
        .status(ErrorCode.XXX.httpStatus)             // ErrorCode에서 status 추출 (상수 하드코딩 지양)
        .body(ErrorResponse.of(ErrorCode.XXX))
}
```

**핵심 규칙**:
- `HttpStatus.XXX`를 핸들러에서 직접 하드코딩하지 않는다 — `ErrorCode.XXX.httpStatus` 사용
- 로그 레벨은 §5 정책을 따른다
- 필드별 errors 맵이 필요한 경우 `ErrorResponse.of(errorCode, errors)` 팩토리 사용
- 상세 detail을 응답 `message`에 담아야 하면 `ErrorResponse.of(errorCode, detail)` 사용

### 3.4 핸들러 선언 순서 — 관례

명시적 예외 타입이 `Exception` catch-all보다 우선 적용되므로 Spring이 가장 구체적인 핸들러를 선택합니다. 순서는 중요하지 않지만, **가독성을 위해 카탈로그(§3.1)와 같은 순서**로 작성합니다:

```
1. BusinessException          (비즈니스 예외 — 가장 중요)
2. Validation 관련 (Bind, MethodArgumentNotValid, TypeMismatch, HttpMessageNotReadable)
3. JPA/DB 관련 (OptimisticLock, DataIntegrity)
4. 라우팅/인프라 관련 (NoResourceFound, Redis)
5. Exception catch-all        (항상 맨 아래)
```

---

## 4. `ErrorResponse` 구조

모든 에러 응답은 `ErrorResponse`로 통일. 3개 팩토리 메서드로 모든 사용처 커버.

```kotlin
// global/exception/ErrorResponse.kt
data class ErrorResponse(
    val code: String,                      // "U409-1"
    val message: String,                   // 사용자 노출 메시지 (한국어)
    val errors: Map<String, String> = emptyMap(),  // 필드별 검증 에러
    val timestamp: Instant = Instant.now(),
) {
    companion object {
        fun of(errorCode: ErrorCode): ErrorResponse                          // 기본
        fun of(errorCode: ErrorCode, errors: Map<String, String>): ErrorResponse  // Bean Validation
        fun of(errorCode: ErrorCode, detail: String): ErrorResponse          // detail override
    }
}
```

### 4.1 응답 예시

```json
// 기본 (ErrorResponse.of(ErrorCode.USER_NOT_FOUND))
{
  "code": "U404",
  "message": "사용자를 찾을 수 없습니다.",
  "errors": {},
  "timestamp": "2026-04-17T09:30:00Z"
}

// Bean Validation 실패 (ErrorResponse.of(INVALID_INPUT, fieldErrors))
{
  "code": "C400",
  "message": "잘못된 입력입니다.",
  "errors": {
    "loginId": "로그인 ID는 필수입니다",
    "phone": "휴대폰 번호 형식이 올바르지 않습니다"
  },
  "timestamp": "2026-04-17T09:30:00Z"
}

// detail override (ErrorResponse.of(INVALID_INPUT, "Invalid value for 'status'. Accepted values: READY, OPEN, CLOSED"))
{
  "code": "C400",
  "message": "Invalid value for 'status'. Accepted values: READY, OPEN, CLOSED",
  "errors": {},
  "timestamp": "2026-04-17T09:30:00Z"
}
```

### 4.2 ErrorResponse 필드 추가 시 주의

`errors` / `timestamp`는 기본값이 있으므로 기존 코드를 깨지 않고 추가 가능. 새 필드가 필요할 때:

- **기본값(빈 맵/null/Instant.now) 제공** → 기존 팩토리 호환성 유지
- 예: `path: String? = null` (요청 URL 추적용) — 기존 `ErrorResponse.of(...)` 호출은 여전히 유효

---

## 5. 로깅 레벨 정책

**규칙**: HTTP 응답의 **4xx = `log.warn`, 5xx = `log.error(e)`** (스택트레이스 포함).

### 5.1 레벨 선택 기준

| 레벨 | 대상 | 스택트레이스 | 예 |
|------|------|-------------|-----|
| `warn { "..." }` | **클라이언트 귀책** (4xx) | ❌ | Business exception (`U409-1`), Validation 실패, JSON parse, Type mismatch, OptimisticLock |
| `error(e) { "..." }` | **서버 귀책** (5xx) | ✅ (원인 파악 필수) | Redis unavailable, Unexpected (catch-all 500) |
| `info { "..." }` | **정상 흐름 이벤트** | ❌ | 사용 자제 (핸들러 내 사용 안 함) |
| `debug { "..." }` | **개발 디버그 전용** | ❌ | 핸들러에는 사용하지 않음 |

### 5.2 왜 4xx는 스택트레이스 없이 `warn`인가

- 4xx는 **정상 작동 중인 요청 거절** — "잘못된 입력이 왔다"는 사실이 중요, 언제/어디서 예외가 throw됐는지는 ErrorCode·메시지로 충분
- 스택트레이스가 붙으면 로그 볼륨이 폭증하고, 진짜 버그(5xx)와 섞여 추적이 어려워짐
- 악의적 반복 호출 시 `warn`은 별도 rate-limit 없이도 볼륨 통제가 상대적으로 용이

### 5.3 왜 5xx는 `error(e)`로 스택트레이스를 남기는가

- 5xx는 **서버 버그 또는 인프라 장애** — 원인 파악을 위해 **스택트레이스 필수**
- `log.error(e) { "..." }` — `e`를 첫 인자로 전달해야 `kotlin-logging`이 stack을 기록 (람다는 메시지만)

```kotlin
// ✅ 스택트레이스 기록됨
log.error(e) { "Unexpected error" }

// ❌ 스택트레이스 누락 — 람다 안에 e.message만 들어가고 trace 없음
log.error { "Unexpected error: ${e.message}" }
```

### 5.4 민감정보 로깅 금지

ErrorCode 디버그 컨텍스트(§2.2)에도 **비밀번호·토큰·주민번호 등은 로깅 금지**.

```kotlin
// ❌ 평문 비밀번호가 로그에 남음 (disaster)
throw BusinessException(ErrorCode.USER_CREDENTIAL_INVALID, "rawPassword=$rawPassword")

// ✅ 식별자만
throw BusinessException(ErrorCode.USER_CREDENTIAL_INVALID, "loginId=$loginId")

// ✅ 또는 컨텍스트 없이
throw BusinessException(ErrorCode.USER_CREDENTIAL_INVALID)
```

PII 마스킹이 필요하면 `global/common/PiiMask.kt`(`PiiMask.phone(...)`, `PiiMask.email(...)`) 사용.

---

## 6. Cause Chain Unwrapping

Jackson·Hibernate 등 프레임워크가 **원인 예외를 래핑**해서 던지는 경우가 있습니다. 우리가 Deserializer 안에서 `BusinessException`을 던졌는데, Spring MVC가 `HttpMessageNotReadableException`으로 감싸버리면 `handleNotReadable`이 일반 400으로 처리 → 원래 의도한 도메인 ErrorCode를 잃음.

### 6.1 현재 구현된 unwrap — `handleNotReadable`

```kotlin
@ExceptionHandler(HttpMessageNotReadableException::class)
fun handleNotReadable(e: HttpMessageNotReadableException): ResponseEntity<ErrorResponse> {
    val cause = e.cause

    // cause.cause까지 파고들어 BusinessException을 꺼냄
    if (cause?.cause is BusinessException) {
        val biz = cause.cause as BusinessException
        log.warn { "JSON parse error (business): ${biz.message}" }
        return ResponseEntity.status(biz.errorCode.httpStatus).body(ErrorResponse.of(biz.errorCode))
    }

    // BusinessException이 아니면 InvalidFormatException·MismatchedInputException 등으로 분기
    val errorMessage = when (cause) {
        is InvalidFormatException -> { /* enum 후보 값 추출 */ }
        is MismatchedInputException -> { /* 필드명 추출 */ }
        else -> "Invalid request format"
    }
    return ResponseEntity.status(HttpStatus.BAD_REQUEST)
        .body(ErrorResponse.of(ErrorCode.INVALID_INPUT, errorMessage))
}
```

### 6.2 왜 `cause.cause`인가 (2단 depth)

- Jackson이 deserializer의 예외를 `JsonMappingException`으로 래핑
- Spring MVC가 그걸 다시 `HttpMessageNotReadableException`으로 래핑
- 결과: `HttpMessageNotReadableException → JsonMappingException → BusinessException`
- 따라서 `e.cause`(=JsonMappingException) `.cause`(=BusinessException)로 2단 언랩

`PhoneNumberDeserializer.kt`에서 `BusinessException(ErrorCode.INVALID_INPUT)`을 throw하면 이 경로를 탑니다.

### 6.3 unwrap 일반 패턴 — `generateSequence`

2단 depth 이상 예외 체인을 순회해야 할 경우(거의 드물지만) `generateSequence`가 관용적:

```kotlin
// 첫 번째 BusinessException cause를 찾음 (없으면 null)
val biz = generateSequence(e as Throwable?) { it.cause }
    .filterIsInstance<BusinessException>()
    .firstOrNull()

if (biz != null) {
    return ResponseEntity.status(biz.errorCode.httpStatus).body(ErrorResponse.of(biz.errorCode))
}
```

현재 프로젝트에선 2단 고정 패턴(`cause.cause`)으로 충분하므로 위 패턴은 예비.

### 6.4 새 Deserializer/Converter 작성 시 가이드

커스텀 Jackson Deserializer / Converter에서 예외를 던질 때:

- 비즈니스 의미가 있으면 **`BusinessException` 던져라** (catch·재래핑 없이 그대로)
- `handleNotReadable`이 알아서 cause chain을 풀어 도메인 ErrorCode로 복원
- 예외를 잡아서 `IllegalArgumentException`으로 바꿔 던지면 BusinessException 체인이 끊겨 일반 400이 나감 (안티패턴)

```kotlin
// ✅ Deserializer
class PhoneNumberDeserializer : JsonDeserializer<String>() {
    override fun deserialize(p: JsonParser, ctxt: DeserializationContext): String {
        val raw = p.text
        if (!UserPattern.PHONE.matches(raw)) {
            throw BusinessException(ErrorCode.INVALID_INPUT, "invalid phone format: $raw")
        }
        return raw
    }
}

// ❌ BusinessException 체인 끊김 → 도메인 ErrorCode 잃음
throw IllegalArgumentException("invalid phone")
```

---

## 7. 체크리스트 / 트러블슈팅 / FAQ

### 7.1 ErrorCode 추가 시 체크리스트

- [ ] **Enum 이름**: `{DOMAIN}_{CONDITION}` (공통은 prefix 없음)
- [ ] **code 문자열**: `{Prefix}{HttpStatus}[-{SubCode}]`
- [ ] **message**: 한국어, 사용자 관점. 다음 행동이 명확하도록
- [ ] **중복 확인**: 기존 ErrorCode로 커버 안 되는지 검토 (공통 `NOT_FOUND` 재사용 가능성)
- [ ] **서브코드 필요성**: 클라이언트가 같은 HTTP 상태 내에서 분기 처리하는가?

### 7.2 BusinessException 발생 시 체크리스트

- [ ] ErrorCode가 상황에 정확히 맞는가 (최대한 specific하게)
- [ ] 디버그 컨텍스트(`"id=$id"`)에 **민감정보**(password, token)가 없는가
- [ ] Service/Entity/VO에서 throw (Repository·DTO에서는 금지)
- [ ] 테스트에서 `shouldThrow<BusinessException> { ... }.errorCode shouldBe ErrorCode.X`로 검증

### 7.3 새 핸들러 추가 시 체크리스트

- [ ] 정말 새 핸들러가 필요한가? — BusinessException으로 해결되지 않는가
- [ ] HTTP status를 하드코딩하지 않고 `ErrorCode.X.httpStatus`를 쓰는가
- [ ] 로그 레벨이 §5 정책(4xx=warn, 5xx=error)에 맞는가
- [ ] 5xx는 `log.error(e) { ... }`로 스택트레이스를 남기는가 (`error { ... }` 아님)

### 7.4 FAQ

**Q1. `RuntimeException`을 직접 던져도 되나요?**
→ ❌ 금지. 비즈니스 예외는 `BusinessException(errorCode)` 경로로 통일해야 `GlobalExceptionHandler`가 도메인 ErrorCode를 보존해서 응답합니다. `RuntimeException`은 catch-all `handleUnexpected`로 가서 500이 됩니다.

**Q2. `require(condition) { "message" }`를 Entity에서 써도 되나요?**
→ 도메인 의미가 있다면 `BusinessException`으로. `require`는 `IllegalArgumentException`을 던져 500이 됩니다. 단, 순수 "프로그래밍 계약 위반"(예: 내부 함수 인자 가드)에는 `require`/`check` 허용.

```kotlin
// ✅ 도메인 불변식 — BusinessException
init {
    if (!startedAt.isBefore(endedAt)) {
        throw BusinessException(ErrorCode.INVALID_DATE_RANGE, "startedAt=$startedAt, endedAt=$endedAt")
    }
}

// ⚠️ 순수 계약 위반 — require 허용 (하지만 드문 경우)
private fun internalHelper(list: List<Int>) {
    require(list.isNotEmpty()) { "list must not be empty" }  // 호출자 버그
}
```

**Q3. Controller에서 `try/catch`로 BusinessException을 잡아 재처리해도 되나요?**
→ ❌ 금지. `GlobalExceptionHandler`가 일관되게 처리하도록 Controller는 throw를 그대로 흘려보냅니다. "특정 예외에서 다른 응답을 만들고 싶다" → 새 ErrorCode + Service 로직으로 해결.

**Q4. 같은 엔드포인트에서 여러 이유로 400이 발생하는데 모두 `INVALID_INPUT`으로 하면 디버깅이 힘들어요.**
→ 디버그 컨텍스트(§2.2)로 해결: `BusinessException(ErrorCode.INVALID_INPUT, "amount=$amount, reason=negative")`. 클라이언트 응답은 동일하지만 로그에 원인이 남습니다. 클라이언트가 분기 처리해야 한다면 새 SubCode 추가.

**Q5. Testcontainers 환경에서 `DataIntegrityViolationException`을 의도적으로 유발해 테스트할 때?**
→ `handleDataIntegrity`가 409 `CONFLICT`로 변환하므로 L4 Integration 테스트에서 응답 status·body를 검증하면 됩니다. Service 레벨(L3)에서는 `BusinessException`으로 한번 감싸 더 구체적인 ErrorCode로 재throw하는 것도 가능(중복 검증을 선제 쿼리로 하는 경우).

**Q6. `@Valid` 실패의 `errors` 맵 key가 nested 필드일 때 (`address.zipCode`)?**
→ Bean Validation이 dotted path를 그대로 제공. 현재 구현은 `BindingResult.fieldErrors.associate { it.field to ... }`이므로 nested 지원됨. 프론트는 `errors["address.zipCode"]`로 접근.

**Q7. `ErrorResponse.timestamp`를 클라이언트가 UTC로 받나요?**
→ `Instant`는 항상 UTC. Jackson이 ISO-8601 문자열(`2026-04-17T09:30:00Z`)로 직렬화. `application.yml`에 `spring.jackson.serialization.write-dates-as-timestamps=false` 설정 전제.

---

## 관련 문서

- [`DTO_WRITE_GUIDE.md`](DTO_WRITE_GUIDE.md) — Bean Validation / `@field:` target / 정규식 단일 출처
- [`JPA_WRITE_GUIDE.md`](JPA_WRITE_GUIDE.md) — `findByIdOrNull + ?: throw` 패턴 / Entity 불변식
- [`TEST_WRITE_GUIDE.md`](TEST_WRITE_GUIDE.md) — `shouldThrow<BusinessException>` 검증 패턴
- `CLAUDE.md` — 프로젝트 컨벤션 맵
- `src/main/kotlin/com/bubaum/buws/global/exception/` — 구현 (ErrorCode · BusinessException · ErrorResponse · GlobalExceptionHandler)
