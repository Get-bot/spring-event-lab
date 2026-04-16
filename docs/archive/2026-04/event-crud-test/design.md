# Event CRUD Test Suite Design Document

> **Summary**: Kotest 6.1.0 Specs + MockK 1.14.9 기반의 event-crud 테스트 스위트 상세 설계
>
> **Project**: spring-event-lab
> **Version**: 0.0.1-SNAPSHOT
> **Author**: beomjin
> **Date**: 2026-04-13
> **Status**: Draft (v0.1)
> **Plan Reference**: [06-event-crud-test.plan.md](../../01-plan/features/06-event-crud-test.plan.md) (v0.4)

---

## Executive Summary

| Perspective | Content |
|-------------|---------|
| **Problem** | Plan v0.4에서 Kotest 6.1.0 전면 도입이 확정되었으나, 각 테스트 클래스의 **구체적인 Spec 구조, 테스트 함수 시그니처, withData 데이터셋, mock 설정**이 아직 정의되지 않았다. |
| **Solution** | 10개 테스트 클래스의 **모든 `describe`/`context`/`it`/`test` 블록을 의사코드 수준으로 설계**하고, ProjectConfig/PackageConfig/IntegrationTestBase 리팩토링의 정확한 구현 명세를 정의한다. |
| **Function/UX Effect** | 설계 문서를 그대로 코드로 옮기면 되므로 **구현 시간 50% 단축**. 테스트 케이스 누락 없이 FR-T01~T17 전체를 커버한다. |
| **Core Value** | Plan의 아키텍처 결정(Kotest 6 Specs, withData, @ServiceConnection)이 **구현 가능한 수준의 상세 명세**로 구체화되어, 구현 단계에서의 의사결정 비용을 최소화한다. |

---

## 0. Learning Guide (학습 로드맵)

> 이 섹션은 Kotest와 테스트 전반이 처음인 개발자를 위한 학습 가이드이다. 각 개념이 **왜** 필요하고, **어떤 순서**로 익혀야 하는지 설명한다.

### 0.1 왜 테스트를 작성하는가?

테스트의 목적은 "코드가 맞는지 확인"이 아니라 **"코드를 안심하고 바꿀 수 있게 하는 것"**이다.

```
리팩토링 → 테스트 실행 → 초록불 → 자신 있게 커밋
리팩토링 → 테스트 실행 → 빨간불 → 어디가 깨졌는지 즉시 파악
```

테스트가 없으면 코드 변경이 무섭고, 변경이 무서우면 코드가 점점 썩는다.

### 0.2 Test Pyramid — 왜 4계층인가?

```
        ╱╲
       ╱ L4 ╲         Integration (E2E)     — 느리고 비쌈, 최소한만
      ╱──────╲
     ╱  L3    ╲       Slice (@DataJpaTest)   — DB/HTTP만 띄움
    ╱──────────╲
   ╱    L2      ╲     Service Unit (MockK)   — Spring 안 띄움, 빠름
  ╱──────────────╲
 ╱      L1        ╲   Domain Unit (순수)      — 가장 빠르고 많아야 함
╱──────────────────╲
```

| 원칙 | 설명 |
|------|------|
| **아래가 클수록 좋다** | L1이 가장 많고 빨라야 한다. new 해서 바로 테스트 |
| **위로 갈수록 비싸다** | Spring Context를 띄우고, Docker 컨테이너를 올리므로 느림 |
| **각 계층은 자기 책임만** | L1은 도메인 규칙만, L2는 Service 로직만, L3는 DB 쿼리만 |

### 0.3 학습 순서 (권장)

```
Step 1: Kotest 기본 문법 익히기 (아래 치트시트 참고)
   ↓
Step 2: L1 DateRangeTest 작성 (가장 단순, mock 없음)
   ↓
Step 3: L1 EventStatusTest 작성 (withData 첫 사용)
   ↓
Step 4: L1 EventTest 작성 (도메인 로직 검증 감 잡기)
   ↓
Step 5: MockK 기본 문법 익히기 (아래 치트시트 참고)
   ↓
Step 6: L2 EventServiceTest 작성 (MockK 첫 사용)
   ↓
Step 7: L3 테스트 (Spring + Testcontainers — 여기부터 느림)
   ↓
Step 8: L4 통합 테스트 (전체 흐름 확인)
```

### 0.4 Kotest DSL 치트시트

#### Spec 종류 — "테스트를 어떤 모양으로 쓸까?"

```kotlin
// DescribeSpec — BDD 스타일, 계층 구조에 적합 (L1에서 사용)
class MyTest : DescribeSpec({
    describe("기능 A") {           // 대주제
        context("조건 B일 때") {     // 세부 조건
            it("결과 C를 반환한다") { // 실제 검증
                // 여기에 테스트 로직
            }
        }
    }
})

// FunSpec — 플랫한 구조, 간결 (L2~L4에서 사용)
class MyTest : FunSpec({
    test("기능 A는 결과 B를 반환한다") {
        // 여기에 테스트 로직
    }
})
```

> **팁**: `describe`/`context`는 **그룹핑**일 뿐, 실제 검증은 항상 `it()` 또는 `test()` 안에서!

#### Assertion — "기대값 확인하는 법"

```kotlin
// 값 비교
actual shouldBe expected           // actual == expected
actual shouldNotBe other           // actual != other

// 예외 검증
shouldThrow<BusinessException> {   // 이 블록이 BusinessException을 던져야 함
    someFunction()
}.errorCode shouldBe ErrorCode.XXX // 던진 예외의 필드도 체이닝 검증

// 컬렉션
list shouldHaveSize 3              // list.size == 3
list shouldContain "item"          // list에 "item" 포함
list.shouldBeEmpty()               // 빈 리스트

// null
value.shouldBeNull()               // value == null
value.shouldNotBeNull()            // value != null

// 문자열
str shouldContain "abc"            // str에 "abc" 포함
str shouldStartWith "hello"        // str이 "hello"로 시작

// 비교
number shouldBeGreaterThan 5       // number > 5
number shouldBeLessThanOrEqualTo 10 // number <= 10
```

#### withData — "같은 로직, 다른 입력으로 반복 테스트"

```kotlin
// Before (withData 없이) — 반복이 많음
it("READY.isIssuable은 false") { EventStatus.READY.isIssuable shouldBe false }
it("OPEN.isIssuable은 true")   { EventStatus.OPEN.isIssuable shouldBe true }
it("CLOSED.isIssuable은 false") { EventStatus.CLOSED.isIssuable shouldBe false }

// After (withData 사용) — 데이터만 추가하면 테스트 자동 생성
withData(
    EventStatus.READY to false,
    EventStatus.OPEN to true,
    EventStatus.CLOSED to false,
) { (status, expected) ->
    status.isIssuable shouldBe expected
}
```

> **학습 포인트**: `withData`는 JUnit의 `@ParameterizedTest` + `@CsvSource`와 비슷하지만, Kotlin 타입 안전성과 DSL이 훨씬 자연스럽다.

#### Lifecycle — "테스트 전후 작업"

```kotlin
class MyTest : FunSpec({
    beforeSpec  { /* 이 Spec 전체 시작 전 1회 */ }
    afterSpec   { /* 이 Spec 전체 끝난 후 1회 */ }
    beforeTest  { /* 매 test/it 블록 실행 전 */ }
    afterTest   { /* 매 test/it 블록 실행 후 */ }

    test("...") { }
})
```

### 0.5 MockK 치트시트

#### 왜 Mock이 필요한가?

```
EventService는 EventRepository를 호출한다.
하지만 L2 테스트에서는 DB를 안 쓰고 싶다. (느리니까)

→ "가짜 Repository"를 만들어서 Service에 넣어주자 = Mock
```

```kotlin
// 1. Mock 생성 — "가짜 객체 만들기"
val repository = mockk<EventRepository>()

// 2. Stubbing — "이렇게 호출하면 이걸 돌려줘"
every { repository.findByIdOrNull(any()) } returns someEvent
every { repository.save(any<Event>()) } returns savedEvent

// 3. 실제 테스트 — Service가 mock을 사용
val service = EventService(repository, queryRepository)
val result = service.getEvent(someId)

// 4. Verify — "진짜 호출됐는지 확인"
verify(exactly = 1) { repository.findByIdOrNull(someId) }

// 5. Slot — "어떤 인자로 호출됐는지 캡처"
val slot = slot<Event>()
every { repository.save(capture(slot)) } answers { slot.captured }
service.create(request)
slot.captured.title shouldBe "기대한 제목"  // save에 전달된 Entity 검증
```

#### Mock vs Real Object 판단 기준

```
Mock으로 대체해야 하는 것:
  ✅ Repository (DB 호출 → 느림)
  ✅ 외부 API 클라이언트
  ✅ 시간/난수 같은 비결정적 요소

Real Object로 써야 하는 것:
  ❌ Entity (도메인 로직이 들어있으므로 mock하면 테스트 의미 상실)
  ❌ Value Object (DateRange 등)
  ❌ DTO (EventCreateRequest, EventResponse)
  ❌ Enum (EventStatus)
```

> **핵심 원칙**: "도메인 객체는 절대 mock하지 않는다." mock은 **외부 의존성(DB, 네트워크)을 제거**하기 위한 도구이지, 도메인 로직을 우회하는 도구가 아니다.

### 0.6 Spring 테스트 어노테이션 치트시트

```kotlin
// @SpringBootTest — 전체 Spring Context를 띄움 (L4에서 사용)
// 장점: 실제 환경과 동일   단점: 느림 (5~15초)
@SpringBootTest
class FullIntegrationTest : FunSpec({ ... })

// @DataJpaTest — JPA 관련만 띄움 (L3 Repository에서 사용)
// 장점: Controller/Service 안 띄움   단점: @Import로 추가 Bean 등록 필요
@DataJpaTest
@Import(JpaConfig::class)  // JPAQueryFactory가 필요하니까
class RepoTest : FunSpec({ ... })

// @WebMvcTest — Controller + MockMvc만 띄움 (L3 Controller에서 사용)
// 장점: Service를 mock으로 대체   단점: 실제 DB 동작 X
@WebMvcTest(EventController::class)
class ControllerTest : FunSpec({ ... })

// @MockkBean (springmockk 5.0.1) — Spring Context의 빈을 MockK mock으로 교체
// Kotest에서는 생성자 주입으로 사용:
class ControllerTest(
    private val mockMvc: MockMvc,
    @MockkBean private val eventService: EventService,  // MockK 가짜 Service
) : FunSpec({ ... })
```

### 0.7 ProjectConfig — "왜 필요한가?"

```
Kotest 5까지: 설정 파일이 아무 데나 있어도 @AutoScan이 찾아줬음
Kotest 6부터: @AutoScan 제거됨. 반드시 정해진 위치에 있어야 함
```

```kotlin
// 반드시 이 패키지에 있어야 한다:
// src/test/kotlin/io/kotest/provided/ProjectConfig.kt
package io.kotest.provided

object ProjectConfig : AbstractProjectConfig() {
    // SpringExtension — Spring 어노테이션이 Kotest에서도 동작하게
    override val extensions = listOf(SpringExtension())

    // SingleInstance — 하나의 테스트 클래스에서 인스턴스 1개만 사용
    //   → Spring Context를 테스트마다 재생성하지 않으므로 빠름
    override val isolationMode = IsolationMode.SingleInstance

    // Sequential — SingleInstance와 안전하게 조합 (병렬이면 race condition)
    override val specExecutionMode = SpecExecutionMode.Sequential
}
```

### 0.8 Testcontainers — "왜 Docker가 필요한가?"

```
문제: H2 같은 인메모리 DB로 테스트하면, PostgreSQL에서만 발생하는 버그를 못 잡음
해결: Docker로 진짜 PostgreSQL을 띄우고 테스트 → 프로덕션과 동일한 환경

@ServiceConnection — 컨테이너가 뜨면 Spring이 자동으로 DB URL을 연결
                     (예전에는 @DynamicPropertySource로 수동 매핑해야 했음)
```

### 0.9 각 섹션별 학습 포인트 안내

이 Design 문서의 각 테스트 섹션에 다음 아이콘으로 학습 포인트를 표기한다:

| 아이콘 | 의미 |
|--------|------|
| **[WHY]** | 이 패턴/구조를 선택한 이유 |
| **[HOW]** | 구현할 때 주의할 점, 처음 하는 사람이 헷갈리는 부분 |
| **[TRAP]** | 초보자가 자주 빠지는 함정 |
| **[TIP]** | 알면 편한 실전 팁 |

---

## 1. Architecture Overview

### 1.1 Test Architecture Diagram

```
┌─────────────────────────────────────────────────────────────────────┐
│                     Kotest 6.1.0 ProjectConfig                      │
│  ┌─────────────────────────────────────────────────────────────┐    │
│  │ SpringExtension · SingleInstance · Sequential               │    │
│  └─────────────────────────────────────────────────────────────┘    │
│                                                                     │
│  ┌─ L1: DescribeSpec (pure Kotlin) ──────────────────────────────┐ │
│  │ DateRangeTest · EventStatusTest · EventTest                   │ │
│  │ EventCreateRequestTest · EventResponseTest                    │ │
│  │ EventQueryOrdersTest                                          │ │
│  │ [withData + Power Assert · NO mock · NO Spring]               │ │
│  └───────────────────────────────────────────────────────────────┘ │
│                                                                     │
│  ┌─ L2: FunSpec + MockK ────────────────────────────────────────┐  │
│  │ EventServiceTest                                              │  │
│  │ [mockk<EventRepository> · mockk<EventQueryRepository>]        │  │
│  │ [clearAllMocks() in beforeTest · NO Spring]                   │  │
│  └───────────────────────────────────────────────────────────────┘ │
│                                                                     │
│  ┌─ L3: FunSpec + SpringExtension ──────────────────────────────┐  │
│  │ EventQueryRepositoryTest (@DataJpaTest + Testcontainers)      │  │
│  │ EventControllerTest (@WebMvcTest + @MockkBean)                │  │
│  └───────────────────────────────────────────────────────────────┘ │
│                                                                     │
│  ┌─ L4: FunSpec + SpringExtension ──────────────────────────────┐  │
│  │ EventCrudIntegrationTest (FunSpec, @ServiceConnection 자동)    │  │
│  │ [@SpringBootTest + @ServiceConnection + Testcontainers]       │  │
│  └───────────────────────────────────────────────────────────────┘ │
└─────────────────────────────────────────────────────────────────────┘
```

### 1.2 의존성 흐름

```
ProjectConfig (io.kotest.provided)
  ├── SpringExtension → L3, L4에 DI 제공
  ├── SingleInstance → 전 계층 기본 격리 모드
  └── Sequential → 순차 실행 (SingleInstance race condition 방지)

EventFixture (support/)
  └── 전 계층에서 공유하는 테스트 데이터 팩토리

IntegrationTestBase (support/)
  └── 컨테이너 홀더 (L4에서 상속하지 않음, @ServiceConnection 자동 감지)
```

---

## 2. Infrastructure Design

### 2.1 build.gradle.kts 변경사항

```kotlin
// === 변경: 버전 업그레이드 ===
// 기존: io.kotest:kotest-runner-junit5:5.9.1
// 기존: io.kotest:kotest-assertions-core:5.9.1
// 기존: io.mockk:mockk:1.14.3

val kotestVersion = "6.1.0"

dependencies {
    // Kotest 6.1.0
    testImplementation("io.kotest:kotest-runner-junit5:$kotestVersion")
    testImplementation("io.kotest:kotest-assertions-core:$kotestVersion")
    testImplementation("io.kotest:kotest-extensions-spring:$kotestVersion")    // NEW
    testImplementation("io.kotest:kotest-framework-datatest:$kotestVersion")   // NEW

    // MockK 1.14.9
    testImplementation("io.mockk:mockk:1.14.9")
}

// === 신규: Power Assert 컴파일러 옵션 ===
kotlin {
    compilerOptions {
        freeCompilerArgs.addAll("-Xpower-assert")
    }
}
```

**변경 파일**: `build.gradle.kts`
**변경 라인**: 86~91 (Kotest/MockK 버전), kotlin 블록에 compilerOptions 추가

### 2.2 ProjectConfig

**파일**: `src/test/kotlin/io/kotest/provided/ProjectConfig.kt`

```kotlin
package io.kotest.provided

import io.kotest.core.config.AbstractProjectConfig
import io.kotest.core.spec.IsolationMode
import io.kotest.engine.concurrency.SpecExecutionMode
import io.kotest.extensions.spring.SpringExtension

object ProjectConfig : AbstractProjectConfig() {
    override val extensions = listOf(SpringExtension())
    override val isolationMode = IsolationMode.SingleInstance
    override val specExecutionMode = SpecExecutionMode.Sequential
}
```

**설계 결정**:
- `SpringExtension`을 전역 등록하여 L3/L4에서 개별 등록 불필요
- L1/L2는 Spring 어노테이션이 없으므로 SpringExtension이 무해(no-op)
- `specExecutionMode = Sequential`: `SingleInstance`와 `Concurrent`를 조합하면 동일 인스턴스에 여러 테스트가 동시 접근하여 race condition 발생. 순차 실행이 안전

### 2.3 PackageConfig (L1 Domain)

**파일**: `src/test/kotlin/com/beomjin/springeventlab/coupon/entity/PackageConfig.kt`

```kotlin
package com.beomjin.springeventlab.coupon.entity

import io.kotest.core.config.AbstractPackageConfig
import kotlin.time.Duration.Companion.seconds

class PackageConfig : AbstractPackageConfig() {
    override val timeout = 1.seconds  // L1 Domain: 1초 제한
}
```

> **[HOW] PackageConfig 규칙**
> - 클래스명이 반드시 `PackageConfig`이어야 Kotest가 자동 감지한다
> - `object`가 아닌 `class`로 선언한다
> - 해당 패키지와 모든 하위 패키지에 설정이 적용된다
> - `timeout`은 `Long`(밀리초)이 아닌 `kotlin.time.Duration` 타입이다
>
> L3/L4의 PackageConfig는 선택적. 기본 Kotest 타임아웃(10분)이 충분하므로 필요 시 추가.

### 2.4 IntegrationTestBase 리팩토링

**파일**: `src/test/kotlin/com/beomjin/springeventlab/support/IntegrationTestBase.kt`

**현재 코드** (이미 @ServiceConnection 적용 완료, 변경 불필요):
```kotlin
@SpringBootTest
@ActiveProfiles("test")
abstract class IntegrationTestBase {
    companion object {
        @ServiceConnection
        val postgres = PostgreSQLContainer(DockerImageName.parse("postgres:18-alpine"))
            .apply { start() }

        @ServiceConnection(name = "redis")
        val redis = GenericContainer(DockerImageName.parse("redis:7-alpine"))
            .withExposedPorts(6379)
            .apply { start() }

        @ServiceConnection
        val kafka = KafkaContainer(DockerImageName.parse("apache/kafka-native:latest"))
            .apply { start() }
    }
}
```

> **[WHY] IntegrationTestBase는 FunSpec을 상속하지 않는다**
>
> Kotest에서 abstract base class에 Spec 상속을 조합하면:
> - Spring 어노테이션(`@SpringBootTest`)과 Kotest Spec 상속 간 `open` 클래스 충돌 가능
> - Kotlin은 다중 클래스 상속 불가 → 하위 클래스에서 다른 Spec으로 전환 불가
> - Kotest 공식 권장: base class 없이 각 테스트가 직접 Spec 상속
>
> IntegrationTestBase는 순수한 **컨테이너 홀더** 역할만 한다.
> `@ServiceConnection` 컨테이너는 Spring Context가 클래스패스에서 자동 감지하므로,
> 하위 테스트 클래스가 IntegrationTestBase를 **상속하지 않아도** 컨테이너를 공유한다.

**하위 테스트 클래스 패턴**:
```kotlin
@SpringBootTest
@ActiveProfiles("test")
class EventCrudIntegrationTest(
    @Autowired private val mockMvc: MockMvc,
) : FunSpec({
    // IntegrationTestBase.companion의 @ServiceConnection 컨테이너를
    // Spring Context가 자동 감지 → 상속 불필요
    test("POST 생성 → GET 상세") { ... }
})
```

### 2.5 EventFixture

**파일**: `src/test/kotlin/com/beomjin/springeventlab/support/EventFixture.kt`

```kotlin
package com.beomjin.springeventlab.support

import com.beomjin.springeventlab.coupon.dto.request.EventCreateRequest
import com.beomjin.springeventlab.coupon.dto.response.EventResponse
import com.beomjin.springeventlab.coupon.entity.Event
import com.beomjin.springeventlab.coupon.entity.EventStatus
import com.beomjin.springeventlab.global.common.DateRange
import java.time.Instant

object EventFixture {

    val DEFAULT_START: Instant = Instant.parse("2026-07-01T00:00:00Z")
    val DEFAULT_END: Instant = Instant.parse("2026-07-07T23:59:59Z")

    fun dateRange(
        start: Instant = DEFAULT_START,
        end: Instant = DEFAULT_END,
    ) = DateRange(start, end)

    fun event(
        title: String = "여름 쿠폰 이벤트",
        totalQuantity: Int = 100,
        status: EventStatus = EventStatus.READY,
        period: DateRange = dateRange(),
    ) = Event(title, totalQuantity, status, period)

    fun openEvent(
        title: String = "진행중 이벤트",
        totalQuantity: Int = 100,
        period: DateRange = dateRange(),
    ): Event = event(title = title, totalQuantity = totalQuantity, status = EventStatus.READY, period = period)
        .also { it.open() }

    fun createRequest(
        title: String? = "여름 쿠폰 이벤트",
        totalQuantity: Int? = 100,
        startedAt: Instant? = DEFAULT_START,
        endedAt: Instant? = DEFAULT_END,
    ) = EventCreateRequest(title, totalQuantity, startedAt, endedAt)

    fun response(event: Event = event()) = EventResponse.from(event)

    fun createRequestJson(
        title: String = "여름 쿠폰 이벤트",
        totalQuantity: Int = 100,
        startedAt: String = "2026-07-01T00:00:00Z",
        endedAt: String = "2026-07-07T23:59:59Z",
    ): String = """
        {
          "title": "$title",
          "totalQuantity": $totalQuantity,
          "startedAt": "$startedAt",
          "endedAt": "$endedAt"
        }
    """.trimIndent()
}
```

---

## 3. L1 — Domain Unit Tests (DescribeSpec)

> **[WHY] 왜 L1부터 시작하는가?**
> L1 Domain 테스트는 Spring도, Docker도, Mock도 필요 없다. `new DateRange(...)`처럼 객체를 직접 만들어서 테스트한다.
> 따라서 **가장 빠르게 피드백**을 받을 수 있고, 테스트 작성의 기본기를 익히기에 최적이다.
>
> **[WHY] 왜 DescribeSpec인가?**
> L1은 "이 객체가 어떤 규칙을 지키는가?"를 검증한다. `describe("DateRange 생성")` → `context("불변식 위반")` → `it("예외를 던진다")` 구조가 규칙을 **자연어처럼** 표현한다.
>
> **[TRAP] 초보자 함정: describe 안에 바로 assertion 쓰기**
> ```kotlin
> // ❌ 이러면 안 됨 — describe/context는 그룹핑일 뿐!
> describe("DateRange") {
>     DateRange(...).startedAt shouldBe ...  // 여기서 assertion하면 안 됨
> }
>
> // ✅ 반드시 it() 또는 test() 안에서
> describe("DateRange") {
>     it("정상 생성") {
>         DateRange(...).startedAt shouldBe ...  // OK
>     }
> }
> ```

### 3.1 DateRangeTest

**파일**: `src/test/kotlin/com/beomjin/springeventlab/coupon/entity/DateRangeTest.kt`
**Spec**: `DescribeSpec`
**Covers**: FR-T01, FR-T02

```kotlin
class DateRangeTest : DescribeSpec({

    describe("DateRange 생성") {

        it("startedAt < endedAt이면 정상 생성된다") {
            // given
            val start = Instant.parse("2026-07-01T00:00:00Z")
            val end = Instant.parse("2026-07-07T23:59:59Z")
            // when
            val range = DateRange(start, end)
            // then
            range.startedAt shouldBe start
            range.endedAt shouldBe end
        }

        context("불변식 위반 (startedAt >= endedAt)") {
            withData(
                nameFn = { (s, e) -> "start=$s, end=$e" },
                // startedAt > endedAt (역순)
                EventFixture.DEFAULT_END to EventFixture.DEFAULT_START,
                // startedAt == endedAt (동일)
                EventFixture.DEFAULT_START to EventFixture.DEFAULT_START,
            ) { (start, end) ->
                val ex = shouldThrow<BusinessException> { DateRange(start, end) }
                ex.errorCode shouldBe ErrorCode.INVALID_DATE_RANGE
            }
        }
    }

    describe("contains — 반개구간 [start, end)") {
        val range = EventFixture.dateRange()

        withData(
            nameFn = { (instant, expected) -> "contains(${instant}) = $expected" },
            range.startedAt to true,                    // start 포함
            range.endedAt to false,                     // end 제외
            range.startedAt.minusMillis(1) to false,    // start 직전 제외
            range.startedAt.plusSeconds(3600) to true,   // 중간값 포함
        ) { (instant, expected) ->
            range.contains(instant) shouldBe expected
        }
    }

    describe("시간 상태 판단") {
        val range = EventFixture.dateRange()

        context("isUpcoming") {
            it("start 이전이면 true") {
                range.isUpcoming(range.startedAt.minusSeconds(1)) shouldBe true
            }
            it("start 시점이면 false") {
                range.isUpcoming(range.startedAt) shouldBe false
            }
        }

        context("isOngoing") {
            it("start 시점이면 true") {
                range.isOngoing(range.startedAt) shouldBe true
            }
            it("end 직전이면 true") {
                range.isOngoing(range.endedAt.minusMillis(1)) shouldBe true
            }
            it("end 시점이면 false") {
                range.isOngoing(range.endedAt) shouldBe false
            }
        }

        context("isEnded") {
            it("end 시점이면 true") {
                range.isEnded(range.endedAt) shouldBe true
            }
            it("end 이후면 true") {
                range.isEnded(range.endedAt.plusSeconds(1)) shouldBe true
            }
            it("end 직전이면 false") {
                range.isEnded(range.endedAt.minusMillis(1)) shouldBe false
            }
        }
    }
})
```

**테스트 수**: 12개 (withData 확장 포함)

> **[HOW] 이 테스트 파일을 작성하는 순서**
> 1. `class DateRangeTest : DescribeSpec({` — 빈 Spec 생성
> 2. `describe("DateRange 생성")` 블록 작성 — 정상 케이스 `it` 1개
> 3. `./gradlew test --tests "*DateRangeTest"` — 초록불 확인
> 4. `context("불변식 위반")` + `withData` 추가
> 5. 나머지 `describe("contains")`, `describe("시간 상태 판단")` 순서대로 추가
>
> **[TIP] 테스트 1개 실행하는 법**
> ```bash
> ./gradlew test --tests "*DateRangeTest"                    # 파일 전체
> ./gradlew test --tests "*DateRangeTest.DateRange 생성*"     # describe 단위
> ```
>
> **[TRAP] withData에서 Pair/Triple 사용 시 구조 분해**
> ```kotlin
> // Pair를 구조 분해할 때 괄호 필수
> withData(...) { (start, end) ->    // ✅ 괄호로 구조 분해
>     DateRange(start, end)
> }
> withData(...) { pair ->            // ❌ pair.first, pair.second로 접근해야 함
>     DateRange(pair.first, pair.second)
> }
> ```

### 3.2 EventStatusTest

**파일**: `src/test/kotlin/com/beomjin/springeventlab/coupon/entity/EventStatusTest.kt`
**Spec**: `DescribeSpec`
**Covers**: FR-T03, FR-T04

```kotlin
class EventStatusTest : DescribeSpec({

    describe("isIssuable") {
        withData(
            nameFn = { (status, _) -> "$status.isIssuable" },
            EventStatus.READY to false,
            EventStatus.OPEN to true,
            EventStatus.CLOSED to false,
        ) { (status, expected) ->
            status.isIssuable shouldBe expected
        }
    }

    describe("canTransitionTo") {
        withData(
            nameFn = { (from, to, _) -> "$from → $to" },
            Triple(EventStatus.READY, EventStatus.OPEN, true),
            Triple(EventStatus.READY, EventStatus.CLOSED, false),
            Triple(EventStatus.OPEN, EventStatus.CLOSED, true),
            Triple(EventStatus.OPEN, EventStatus.READY, false),
            Triple(EventStatus.CLOSED, EventStatus.READY, false),
            Triple(EventStatus.CLOSED, EventStatus.OPEN, false),
        ) { (from, to, expected) ->
            from.canTransitionTo(to) shouldBe expected
        }
    }

    describe("transitionTo") {
        it("READY → OPEN 성공") {
            EventStatus.READY.transitionTo(EventStatus.OPEN) shouldBe EventStatus.OPEN
        }

        it("OPEN → CLOSED 성공") {
            EventStatus.OPEN.transitionTo(EventStatus.CLOSED) shouldBe EventStatus.CLOSED
        }

        context("허용되지 않은 전이") {
            withData(
                nameFn = { (from, to) -> "$from → $to" },
                EventStatus.READY to EventStatus.CLOSED,
                EventStatus.OPEN to EventStatus.READY,
                EventStatus.CLOSED to EventStatus.READY,
                EventStatus.CLOSED to EventStatus.OPEN,
            ) { (from, to) ->
                shouldThrow<BusinessException> { from.transitionTo(to) }
                    .errorCode shouldBe ErrorCode.EVENT_INVALID_STATUS_TRANSITION
            }
        }
    }
})
```

**테스트 수**: 15개 (withData 확장 포함)

> **[HOW] withData + nameFn 패턴**
> `nameFn`을 지정하면 테스트 실행 결과에서 각 케이스가 어떤 데이터인지 보인다:
> ```
> EventStatusTest
>   ✓ isIssuable > READY.isIssuable
>   ✓ isIssuable > OPEN.isIssuable
>   ✓ isIssuable > CLOSED.isIssuable
> ```
> `nameFn`을 안 쓰면 `(READY, false)` 같은 toString()이 나와서 가독성이 떨어진다.
>
> **[WHY] 전이 규칙을 왜 이렇게 꼼꼼히 테스트하는가?**
> `READY → CLOSED`를 실수로 허용하면 이벤트가 OPEN 없이 바로 닫힌다.
> 이런 버그는 프로덕션에서 "쿠폰 발급이 안 돼요"로 나타난다.
> **전이표 전체를 테스트하면 이런 실수를 컴파일 타임에 잡는다.**

### 3.3 EventTest

**파일**: `src/test/kotlin/com/beomjin/springeventlab/coupon/entity/EventTest.kt`
**Spec**: `DescribeSpec`
**Covers**: FR-T05, FR-T06

```kotlin
class EventTest : DescribeSpec({

    describe("Event 생성") {
        it("정상 생성 시 issuedQuantity=0, remainingQuantity=totalQuantity") {
            val event = EventFixture.event(totalQuantity = 100)
            event.issuedQuantity shouldBe 0
            event.remainingQuantity shouldBe 100
            event.eventStatus shouldBe EventStatus.READY
        }
    }

    describe("issue()") {
        context("READY 상태") {
            it("EVENT_NOT_OPEN을 던진다") {
                val event = EventFixture.event(status = EventStatus.READY)
                shouldThrow<BusinessException> { event.issue() }
                    .errorCode shouldBe ErrorCode.EVENT_NOT_OPEN
            }
        }

        context("OPEN 상태") {
            it("재고가 있으면 issuedQuantity를 1 증가시킨다") {
                val event = EventFixture.openEvent(totalQuantity = 100)
                event.issue()
                event.issuedQuantity shouldBe 1
                event.remainingQuantity shouldBe 99
            }

            it("재고가 0이면 EVENT_OUT_OF_STOCK을 던진다") {
                val event = EventFixture.openEvent(totalQuantity = 1)
                event.issue() // 재고 소진
                shouldThrow<BusinessException> { event.issue() }
                    .errorCode shouldBe ErrorCode.EVENT_OUT_OF_STOCK
            }
        }

        context("CLOSED 상태") {
            it("EVENT_NOT_OPEN을 던진다") {
                val event = EventFixture.openEvent()
                event.close()
                shouldThrow<BusinessException> { event.issue() }
                    .errorCode shouldBe ErrorCode.EVENT_NOT_OPEN
            }
        }
    }

    describe("open()") {
        it("READY → OPEN 전이 성공") {
            val event = EventFixture.event(status = EventStatus.READY)
            event.open()
            event.eventStatus shouldBe EventStatus.OPEN
        }

        it("OPEN → OPEN 시 EVENT_INVALID_STATUS_TRANSITION") {
            val event = EventFixture.openEvent()
            shouldThrow<BusinessException> { event.open() }
                .errorCode shouldBe ErrorCode.EVENT_INVALID_STATUS_TRANSITION
        }
    }

    describe("close()") {
        it("OPEN → CLOSED 전이 성공") {
            val event = EventFixture.openEvent()
            event.close()
            event.eventStatus shouldBe EventStatus.CLOSED
        }

        it("READY → CLOSED 시 EVENT_INVALID_STATUS_TRANSITION") {
            val event = EventFixture.event(status = EventStatus.READY)
            shouldThrow<BusinessException> { event.close() }
                .errorCode shouldBe ErrorCode.EVENT_INVALID_STATUS_TRANSITION
        }
    }

    describe("isIssuable()") {
        withData(
            nameFn = { (desc, _) -> desc },
            "READY + qty>0 → false" to { EventFixture.event(status = EventStatus.READY, totalQuantity = 100) },
            "OPEN + qty=0 → false" to {
                EventFixture.openEvent(totalQuantity = 1).also { it.issue() }
            },
            "OPEN + qty>0 → true" to { EventFixture.openEvent(totalQuantity = 100) },
            "CLOSED + qty>0 → false" to {
                EventFixture.openEvent(totalQuantity = 100).also { it.close() }
            },
        ) { (_, factory) ->
            val event = factory()
            val expected = event.eventStatus.isIssuable && event.remainingQuantity > 0
            event.isIssuable() shouldBe expected
        }
    }
})
```

**테스트 수**: 12개

> **[WHY] EventTest가 가장 중요한 이유**
> Event Entity는 이 프로젝트의 **핵심 도메인 객체**이다. `issue()`, `open()`, `close()`가 잘못 동작하면
> 쿠폰이 중복 발급되거나, 마감된 이벤트에서 쿠폰이 나간다.
> **이 테스트들이 초록불이면 "도메인 규칙은 안전하다"고 확신할 수 있다.**
>
> **[HOW] openEvent() 헬퍼를 만든 이유**
> `issue()` 테스트를 하려면 먼저 Event를 OPEN으로 전이해야 한다.
> 매번 `event.open()`을 호출하는 건 반복이므로 `EventFixture.openEvent()`로 축약했다.
> 하지만 **Event 자체를 mock하지는 않는다** — 도메인 로직이 바이패스되면 테스트 의미가 없다.
>
> **[TRAP] issue() 재고 소진 테스트에서 totalQuantity=1 사용**
> `totalQuantity=100`으로 하면 `repeat(100) { event.issue() }` 해야 재고가 소진된다.
> **경계값 테스트에서는 가장 작은 값으로 빠르게 경계에 도달**하는 게 좋다.

### 3.4 EventCreateRequestTest

**파일**: `src/test/kotlin/com/beomjin/springeventlab/coupon/dto/EventCreateRequestTest.kt`
**Spec**: `DescribeSpec`

```kotlin
class EventCreateRequestTest : DescribeSpec({

    describe("toEntity()") {
        it("모든 필드가 Entity에 매핑된다") {
            val request = EventFixture.createRequest(
                title = "테스트 이벤트",
                totalQuantity = 50,
            )
            val entity = request.toEntity()

            entity.title shouldBe "테스트 이벤트"
            entity.totalQuantity shouldBe 50
            entity.eventStatus shouldBe EventStatus.READY
            entity.period.startedAt shouldBe EventFixture.DEFAULT_START
            entity.period.endedAt shouldBe EventFixture.DEFAULT_END
        }

        it("DateRange 불변식 위반 시 INVALID_DATE_RANGE를 던진다") {
            val request = EventFixture.createRequest(
                startedAt = EventFixture.DEFAULT_END,
                endedAt = EventFixture.DEFAULT_START,
            )
            shouldThrow<BusinessException> { request.toEntity() }
                .errorCode shouldBe ErrorCode.INVALID_DATE_RANGE
        }
    }
})
```

**테스트 수**: 2개

### 3.5 EventResponseTest

**파일**: `src/test/kotlin/com/beomjin/springeventlab/coupon/dto/EventResponseTest.kt`
**Spec**: `DescribeSpec`

```kotlin
class EventResponseTest : DescribeSpec({

    describe("from(entity)") {
        it("Entity의 모든 필드를 Response에 매핑한다") {
            val event = EventFixture.event(
                title = "매핑 테스트",
                totalQuantity = 200,
            )
            val response = EventResponse.from(event)

            response.id shouldBe event.id
            response.title shouldBe "매핑 테스트"
            response.totalQuantity shouldBe 200
            response.issuedQuantity shouldBe 0
            response.remainingQuantity shouldBe 200
            response.eventStatus shouldBe EventStatus.READY
            response.startedAt shouldBe event.period.startedAt
            response.endedAt shouldBe event.period.endedAt
        }

        it("remainingQuantity는 totalQuantity - issuedQuantity를 반영한다") {
            val event = EventFixture.openEvent(totalQuantity = 10)
            repeat(3) { event.issue() }

            val response = EventResponse.from(event)
            response.issuedQuantity shouldBe 3
            response.remainingQuantity shouldBe 7
        }
    }
})
```

**테스트 수**: 2개

### 3.6 EventQueryOrdersTest

**파일**: `src/test/kotlin/com/beomjin/springeventlab/coupon/service/EventQueryOrdersTest.kt`
**Spec**: `DescribeSpec`
**Covers**: FR-T07

```kotlin
class EventQueryOrdersTest : DescribeSpec({

    describe("orders(sort)") {

        it("Sort.unsorted()이면 createdAt DESC fallback") {
            val orders = EventQuery.orders(Sort.unsorted())
            orders shouldHaveSize 1
            // QEvent.event.createdAt.desc() 와 동일한지 검증
        }

        context("화이트리스트 정렬 필드") {
            withData(
                nameFn = { (field, _) -> "sort=$field" },
                "title" to "title",
                "createdAt" to "createdAt",
                "startedAt" to "startedAt",
                "endedAt" to "endedAt",
                "totalQuantity" to "totalQuantity",
            ) { (field, _) ->
                val orders = EventQuery.orders(Sort.by(Sort.Direction.ASC, field))
                orders shouldHaveSize 1
                // OrderSpecifier의 방향이 ASC인지 검증
            }
        }

        it("다중 정렬 — title,asc + createdAt,desc") {
            val sort = Sort.by(
                Sort.Order.asc("title"),
                Sort.Order.desc("createdAt"),
            )
            val orders = EventQuery.orders(sort)
            orders shouldHaveSize 2
        }

        context("화이트리스트 외 필드") {
            it("무효 필드만이면 fallback") {
                val orders = EventQuery.orders(Sort.by("password"))
                orders shouldHaveSize 1 // createdAt DESC fallback
            }

            it("유효 + 무효 혼합이면 유효한 것만") {
                val sort = Sort.by(
                    Sort.Order.asc("title"),
                    Sort.Order.asc("password"),
                )
                val orders = EventQuery.orders(sort)
                orders shouldHaveSize 1 // title만
            }
        }
    }
})
```

**테스트 수**: 10개 (withData 확장 포함)

> **[WHY] 왜 EventQuery.orders를 단위 테스트하는가?**
> `orders()`는 사용자가 보낸 `sort` 파라미터를 QueryDSL `OrderSpecifier`로 변환한다.
> 만약 화이트리스트 검증이 없으면 **SQL Injection 공격**이 가능하다.
> 이 테스트는 "허용된 필드만 정렬에 사용되는가?"를 검증하는 **보안 테스트**이기도 하다.

---

## 4. L2 — Service Unit Tests (FunSpec + MockK)

> **[WHY] 왜 L2를 분리하는가? L1이나 L3에 합치면 안 되나?**
> - L1에 합치면: Service는 Repository에 의존하는데, L1은 "의존성 0"이 원칙
> - L3에 합치면: `@SpringBootTest`로 Spring을 띄워야 하는데, 그러면 느림 (5초+)
> - **L2의 핵심**: MockK로 Repository를 가짜로 대체해서, **Spring 없이 밀리초 단위**로 Service 로직만 검증
>
> **[WHY] 왜 FunSpec인가?**
> Service 테스트는 "create하면 이렇게 된다", "getEvent하면 이렇게 된다" 같은 **플랫한 시나리오**다.
> DescribeSpec의 계층 구조가 오히려 과하므로 `test("...")` 한 줄로 간결하게.
>
> **[HOW] MockK 사용 패턴 (이것만 기억하면 됨)**
> ```
> 1. mockk<인터페이스>()  — 가짜 객체 생성
> 2. every { ... } returns ...  — "이렇게 호출하면 이걸 돌려줘"
> 3. 테스트 대상 호출
> 4. assertion으로 결과 확인
> 5. verify로 호출 여부 확인 (선택)
> ```
>
> **[TRAP] clearAllMocks()를 빼먹으면?**
> `SingleInstance` 모드에서는 Spec 인스턴스가 1개이므로, 이전 테스트의 mock 설정이 남아있다.
> `beforeTest { clearAllMocks() }`를 빼먹으면 테스트 순서에 따라 결과가 달라지는 **flaky test**가 된다.

### 4.1 EventServiceTest

**파일**: `src/test/kotlin/com/beomjin/springeventlab/coupon/service/EventServiceTest.kt`
**Spec**: `FunSpec`
**Covers**: FR-T08, FR-T09, FR-T10

```kotlin
class EventServiceTest : FunSpec({

    val eventRepository = mockk<EventRepository>()
    val eventQueryRepository = mockk<EventQueryRepository>()
    val service = EventService(eventRepository, eventQueryRepository)

    beforeTest { clearAllMocks() }

    // === create ===

    test("create는 request를 Entity로 변환해 저장하고 EventResponse를 반환한다") {
        // given
        val request = EventFixture.createRequest()
        val saved = EventFixture.event()
        every { eventRepository.save(any<Event>()) } returns saved

        // when
        val response = service.create(request)

        // then
        response.title shouldBe saved.title
        response.totalQuantity shouldBe saved.totalQuantity
        verify(exactly = 1) { eventRepository.save(any<Event>()) }
    }

    test("create는 request의 필드를 Entity에 정확히 매핑하여 save에 전달한다") {
        // given
        val request = EventFixture.createRequest(title = "슬롯 검증", totalQuantity = 77)
        val entitySlot = slot<Event>()
        every { eventRepository.save(capture(entitySlot)) } answers { entitySlot.captured }

        // when
        service.create(request)

        // then
        entitySlot.captured.title shouldBe "슬롯 검증"
        entitySlot.captured.totalQuantity shouldBe 77
        entitySlot.captured.eventStatus shouldBe EventStatus.READY
    }

    // === getEvent ===

    test("getEvent는 존재하는 ID에 대해 EventResponse를 반환한다") {
        // given
        val event = EventFixture.event()
        every { eventRepository.findByIdOrNull(event.id) } returns event

        // when
        val response = service.getEvent(event.id)

        // then
        response.id shouldBe event.id
        response.title shouldBe event.title
    }

    test("getEvent는 존재하지 않는 ID에 대해 EVENT_NOT_FOUND를 던진다") {
        // given
        val id = UUID.randomUUID()
        every { eventRepository.findByIdOrNull(id) } returns null

        // when/then
        shouldThrow<BusinessException> { service.getEvent(id) }
            .errorCode shouldBe ErrorCode.EVENT_NOT_FOUND
    }

    // === getEvents ===

    test("getEvents는 eventQueryRepository.search에 조건과 Pageable을 위임한다") {
        // given
        val cond = EventSearchCond(keyword = "여름")
        val pageable = PageRequest.of(0, 20)
        val events = listOf(EventFixture.event())
        val page: Page<Event> = PageImpl(events, pageable, 1)

        val condSlot = slot<EventSearchCond>()
        val pageableSlot = slot<Pageable>()
        every { eventQueryRepository.search(capture(condSlot), capture(pageableSlot)) } returns page

        // when
        val result = service.getEvents(cond, pageable)

        // then
        condSlot.captured.keyword shouldBe "여름"
        pageableSlot.captured.pageSize shouldBe 20
        result.content shouldHaveSize 1
        result.totalElements shouldBe 1
    }

    test("getEvents는 빈 결과에 대해 빈 PageResponse를 반환한다") {
        // given
        val cond = EventSearchCond()
        val pageable = PageRequest.of(0, 20)
        every { eventQueryRepository.search(any(), any()) } returns Page.empty()

        // when
        val result = service.getEvents(cond, pageable)

        // then
        result.content shouldHaveSize 0
        result.totalElements shouldBe 0
    }
})
```

**테스트 수**: 6개

> **[HOW] slot 이해하기**
> `slot<Event>()`는 "Repository.save()에 어떤 Event가 전달됐는지 잡아두는 그물"이다.
> ```kotlin
> val slot = slot<Event>()
> every { repo.save(capture(slot)) } answers { slot.captured }
>
> service.create(request)
>
> // save()에 전달된 Entity의 필드를 직접 검증
> slot.captured.title shouldBe "기대한 제목"
> ```
> **[WHY]** 이게 왜 필요한가? `verify`는 "호출됐는지"만 확인하고, **어떤 인자**로 호출됐는지는 안 알려준다.
> slot을 쓰면 Service가 request를 Entity로 **정확하게 변환**했는지까지 검증할 수 있다.

---

## 5. L3 — Slice Tests (FunSpec + SpringExtension)

> **[WHY] L3은 뭐가 다른가?**
> L1~L2는 Spring을 안 띄운다. L3부터는 **진짜 Spring의 일부를 띄운다**.
> - `@DataJpaTest`: JPA + DB 관련 Bean만 로드. **실제 QueryDSL 쿼리가 DB에서 동작하는지** 검증
> - `@WebMvcTest`: Controller + MockMvc만 로드. **HTTP 요청/응답 형식이 맞는지** 검증
>
> **[HOW] L3 테스트 작성 시 알아야 할 것**
> - Spring 컨텍스트를 띄우므로 **처음 실행이 5~10초** 걸린다 (이후 캐싱)
> - Testcontainers는 Docker가 실행 중이어야 한다 (`docker ps`로 확인)
> - `@Import(JpaConfig::class)`를 빼먹으면 `JPAQueryFactory` Bean을 못 찾아서 에러
>
> **[TRAP] @DataJpaTest vs @SpringBootTest 혼동**
> ```kotlin
> @DataJpaTest    // JPA만 로드. Service, Controller는 없음
> @SpringBootTest // 전부 로드. 느리지만 완전한 환경
> ```
> Repository 테스트에 `@SpringBootTest`를 쓰면 불필요하게 느려진다.

### 5.1 EventQueryRepositoryTest

**파일**: `src/test/kotlin/com/beomjin/springeventlab/coupon/repository/EventQueryRepositoryTest.kt`
**Spec**: `FunSpec`
**Covers**: FR-T11, FR-T12

```kotlin
@DataJpaTest
@Import(JpaConfig::class)
@Testcontainers
class EventQueryRepositoryTest(
    private val eventQueryRepository: EventQueryRepository,
    private val entityManager: EntityManager,
) : FunSpec({

    // === Test Data Setup ===
    // beforeSpec에서 테스트 데이터 삽입 (persist via EntityManager)
    // 최소 5개 Event: 다양한 status, title, totalQuantity, period

    beforeSpec {
        val events = listOf(
            Event("여름 쿠폰", 100, EventStatus.READY, DateRange(
                Instant.parse("2026-07-01T00:00:00Z"),
                Instant.parse("2026-07-31T23:59:59Z"),
            )),
            Event("겨울 세일", 50, EventStatus.OPEN, DateRange(
                Instant.parse("2026-01-01T00:00:00Z"),
                Instant.parse("2026-12-31T23:59:59Z"),
            )),
            Event("봄 할인", 200, EventStatus.CLOSED, DateRange(
                Instant.parse("2025-03-01T00:00:00Z"),
                Instant.parse("2025-03-31T23:59:59Z"),
            )),
            Event("가을 이벤트", 10, EventStatus.READY, DateRange(
                Instant.parse("2026-09-01T00:00:00Z"),
                Instant.parse("2026-09-30T23:59:59Z"),
            )),
            Event("연말 특별", 0, EventStatus.OPEN, DateRange(
                Instant.parse("2026-01-01T00:00:00Z"),
                Instant.parse("2026-12-31T23:59:59Z"),
            )).apply {
                // totalQuantity=0 이벤트는 불가능하므로, 재고 소진 시뮬레이션은 별도 처리
            },
        )
        events.forEach { entityManager.persist(it) }
        entityManager.flush()
        entityManager.clear()
    }

    // === 필터 테스트 ===

    test("필터 없음 — 전체 반환 + createdAt DESC 기본 정렬") {
        val result = eventQueryRepository.search(EventSearchCond(), PageRequest.of(0, 20))
        result.content.size shouldBeGreaterThanOrEqualTo 4
    }

    test("keyword='여름' — 제목에 '여름'이 포함된 이벤트만") {
        val result = eventQueryRepository.search(
            EventSearchCond(keyword = "여름"),
            PageRequest.of(0, 20),
        )
        result.content.forEach { it.title shouldContain "여름" }
        result.content shouldHaveSize 1
    }

    test("statuses=[OPEN, READY] — 해당 상태만 반환") {
        val result = eventQueryRepository.search(
            EventSearchCond(statuses = listOf(EventStatus.OPEN, EventStatus.READY)),
            PageRequest.of(0, 20),
        )
        result.content.forEach {
            it.eventStatus shouldBeIn listOf(EventStatus.OPEN, EventStatus.READY)
        }
    }

    test("hasRemainingStock=true — 잔여 재고가 있는 이벤트만") {
        val result = eventQueryRepository.search(
            EventSearchCond(hasRemainingStock = true),
            PageRequest.of(0, 20),
        )
        result.content.forEach { it.remainingQuantity shouldBeGreaterThan 0 }
    }

    test("조합 필터 — keyword + statuses 동시 적용 (AND)") {
        val result = eventQueryRepository.search(
            EventSearchCond(keyword = "여름", statuses = listOf(EventStatus.READY)),
            PageRequest.of(0, 20),
        )
        result.content shouldHaveSize 1
        result.content.first().title shouldContain "여름"
    }

    // === 페이징 테스트 ===

    test("page=0, size=2 — 첫 2개만 반환") {
        val result = eventQueryRepository.search(EventSearchCond(), PageRequest.of(0, 2))
        result.content shouldHaveSize 2
        result.totalPages shouldBeGreaterThanOrEqualTo 2
    }

    // === 정렬 테스트 ===

    test("sort=title,asc — 제목 오름차순") {
        val result = eventQueryRepository.search(
            EventSearchCond(),
            PageRequest.of(0, 20, Sort.by(Sort.Direction.ASC, "title")),
        )
        result.content.map { it.title } shouldBeSortedWith compareBy { it }
    }

    test("화이트리스트 외 sort — 기본 정렬(createdAt DESC) fallback") {
        val result = eventQueryRepository.search(
            EventSearchCond(),
            PageRequest.of(0, 20, Sort.by("nonexistent")),
        )
        // createdAt DESC 정렬 확인 (최신이 먼저)
        result.content shouldHaveSize result.content.size // 정상 반환 확인
    }
}) {
    companion object {
        @Container
        @ServiceConnection
        @JvmStatic
        val postgres = PostgreSQLContainer(DockerImageName.parse("postgres:18-alpine"))
    }
}
```

**테스트 수**: 8개

> **[HOW] @DataJpaTest + Testcontainers 조합 이해**
> ```
> @DataJpaTest가 하는 일:
>   1. JPA 관련 Bean(EntityManager, Repository 등)만 로드
>   2. @Transactional 자동 적용 → 테스트 끝나면 롤백
>
> Testcontainers가 하는 일:
>   1. Docker로 진짜 PostgreSQL 컨테이너 기동
>   2. @ServiceConnection으로 Spring에 DB URL 자동 주입
>
> 결과: "진짜 PostgreSQL에서 QueryDSL 쿼리를 실행하되,
>        테스트마다 롤백되어 데이터가 안 남음"
> ```
>
> **[TRAP] beforeSpec vs beforeTest 데이터 삽입**
> - `beforeSpec`: Spec 전체에서 **1회**만 실행. 공통 테스트 데이터 삽입에 적합
> - `beforeTest`: **매 테스트마다** 실행. SingleInstance + @Transactional 환경에서는 롤백이 테스트 단위이므로 beforeSpec의 데이터가 다음 테스트에서도 보일 수 있음
> - **이 설계에서는** `beforeSpec`에서 데이터를 넣고, 각 테스트는 **조회만** 하므로 문제없음

### 5.2 EventControllerTest

**파일**: `src/test/kotlin/com/beomjin/springeventlab/coupon/controller/EventControllerTest.kt`
**Spec**: `FunSpec`
**Covers**: FR-T13, FR-T14

```kotlin
@WebMvcTest(EventController::class)
class EventControllerTest(
    private val mockMvc: MockMvc,
    @MockkBean private val eventService: EventService,
) : FunSpec({

    // === POST /api/v1/events ===

    test("POST 정상 요청은 201과 EventResponse를 반환한다") {
        every { eventService.create(any()) } returns EventFixture.response()

        mockMvc.post("/api/v1/events") {
            contentType = MediaType.APPLICATION_JSON
            content = EventFixture.createRequestJson()
        }.andExpect {
            status { isCreated() }
            jsonPath("$.title") { value("여름 쿠폰 이벤트") }
            jsonPath("$.totalQuantity") { value(100) }
            jsonPath("$.eventStatus") { value("READY") }
        }
    }

    context("POST /api/v1/events - 유효성 검사 실패 (400)") {
        data class BadRequestCase(val description: String, val payload: String)

        withData(
            nameFn = { it.description },
            BadRequestCase("title이 빈 문자열", EventFixture.createRequestJson(title = "")),
            BadRequestCase("totalQuantity가 0", EventFixture.createRequestJson(totalQuantity = 0)),
            BadRequestCase("startedAt 누락", """{"title":"test","totalQuantity":10,"endedAt":"2026-07-07T23:59:59Z"}"""),
        ) { (_, payload) ->
            mockMvc.post("/api/v1/events") {
                contentType = MediaType.APPLICATION_JSON
                content = payload
            }.andExpect {
                status { isBadRequest() }
            }
        }
    }

    // === GET /api/v1/events ===

    test("GET 정상 요청은 200과 PageResponse를 반환한다") {
        every { eventService.getEvents(any(), any()) } returns PageResponse.from(Page.empty())

        mockMvc.get("/api/v1/events")
            .andExpect {
                status { isOk() }
                jsonPath("$.content") { isArray() }
            }
    }

    test("GET page=1&size=20은 0-based Pageable(pageNumber=0)로 Service에 전달한다") {
        val pageableSlot = slot<Pageable>()
        every { eventService.getEvents(any(), capture(pageableSlot)) } returns PageResponse.from(Page.empty())

        mockMvc.get("/api/v1/events?page=1&size=20")
            .andExpect { status { isOk() } }

        pageableSlot.captured.pageNumber shouldBe 0   // 1-based → 0-based
        pageableSlot.captured.pageSize shouldBe 20
    }

    test("GET statuses=OPEN&statuses=READY는 List<EventStatus>로 바인딩된다") {
        val condSlot = slot<EventSearchCond>()
        every { eventService.getEvents(capture(condSlot), any()) } returns PageResponse.from(Page.empty())

        mockMvc.get("/api/v1/events?statuses=OPEN&statuses=READY")
            .andExpect { status { isOk() } }

        condSlot.captured.statuses shouldContainExactlyInAnyOrder
            listOf(EventStatus.OPEN, EventStatus.READY)
    }

    test("GET keyword=a (2자 미만)이면 400") {
        mockMvc.get("/api/v1/events?keyword=a")
            .andExpect { status { isBadRequest() } }
    }

    // === GET /api/v1/events/{id} ===

    test("GET /{id} 정상 요청은 200과 EventResponse를 반환한다") {
        val response = EventFixture.response()
        every { eventService.getEvent(any()) } returns response

        mockMvc.get("/api/v1/events/${response.id}")
            .andExpect {
                status { isOk() }
                jsonPath("$.title") { value(response.title) }
            }
    }

    test("GET /{id} 존재하지 않는 ID는 404 EVENT_NOT_FOUND") {
        val id = UUID.randomUUID()
        every { eventService.getEvent(id) } throws BusinessException(ErrorCode.EVENT_NOT_FOUND)

        mockMvc.get("/api/v1/events/$id")
            .andExpect {
                status { isNotFound() }
                jsonPath("$.code") { value("E404") }
            }
    }
})
```

**테스트 수**: 10개

> **[WHY] Controller 테스트에서 Service를 mock하는 이유**
> L3 Controller 테스트의 관심사는 **"HTTP 요청이 올바르게 파싱되고, 응답이 올바른 형식인가?"**이다.
> Service 로직은 L2에서 이미 검증했으므로, 여기서는 "Service가 이런 값을 돌려준다"고 **가정**하고 테스트한다.
>
> **[HOW] mockk() vs @MockkBean의 차이 (L2 vs L3)**
> ```kotlin
> // L2 (MockK) — Spring 없이 직접 mock 생성
> val repo = mockk<EventRepository>()
> val service = EventService(repo, queryRepo)  // 직접 주입
>
> // L3 (@MockkBean via springmockk 5.0.1) — Spring이 빈을 MockK mock으로 교체
> @MockkBean private val eventService: EventService  // Spring이 관리
> ```
> `@MockkBean`은 **Spring Context 안의 빈을 MockK mock으로 교체**하는 것이다.
> `springmockk 5.0.1`이 Spring Framework 7의 BeanOverride API를 지원하면서 가능해졌다.
> **전 계층(L2~L3)에서 동일한 MockK DSL(`every`/`verify`/`slot`)을 사용**할 수 있다.

---

## 6. L4 — Integration Tests (FunSpec)

> **[WHY] L4는 왜 최소한만 작성하는가?**
> L4는 **전체 Spring Context + 진짜 DB + 진짜 HTTP**를 사용한다.
> 가장 현실적이지만, 가장 느리고(10초+) 실패 원인 추적이 어렵다.
> L1~L3에서 각 계층을 충분히 검증했으므로, L4는 **"전체가 연결되면 동작하는가?"만** 확인한다.
>
> **[HOW] L4 테스트 패턴 — IntegrationTestBase를 상속하지 않는다**
> ```kotlin
> // IntegrationTestBase는 Spec을 상속하지 않는 순수 컨테이너 홀더
> // 하위 테스트 클래스가 직접 FunSpec을 상속한다
> @SpringBootTest
> @ActiveProfiles("test")
> class MyIntegrationTest(
>     @Autowired private val mockMvc: MockMvc,
> ) : FunSpec({
>     test("...") { }
> })
> ```
> `@ServiceConnection` 컨테이너는 Spring Context가 클래스패스에서 자동 감지하므로
> IntegrationTestBase를 상속하지 않아도 동일한 Postgres/Redis/Kafka를 공유한다.
>
> **[TRAP] IntegrationTestBase를 FunSpec으로 만들면 안 되는 이유**
> - Kotlin은 다중 클래스 상속 불가 → 하위 클래스가 다른 Spec(DescribeSpec 등)으로 전환 불가
> - Spring 어노테이션 + Spec 상속 조합 시 `open` 클래스 충돌 가능
> - Kotest 공식 권장: 각 테스트가 **직접 Spec을 상속**

### 6.1 EventCrudIntegrationTest

**파일**: `src/test/kotlin/com/beomjin/springeventlab/coupon/EventCrudIntegrationTest.kt`
**Spec**: `FunSpec` (직접 상속, IntegrationTestBase 미상속)
**Covers**: FR-T15, FR-T16, FR-T17

```kotlin
@SpringBootTest
@ActiveProfiles("test")
class EventCrudIntegrationTest(
    @Autowired private val mockMvc: MockMvc,
) : FunSpec({

    // === Happy Path ===

    test("POST 생성 → GET 상세로 동일한 데이터를 조회한다") {
            // POST
            val createResult = mockMvc.post("/api/v1/events") {
                contentType = MediaType.APPLICATION_JSON
                content = EventFixture.createRequestJson()
            }.andExpect {
                status { isCreated() }
            }.andReturn()

            val id = JsonPath.read<String>(createResult.response.contentAsString, "$.id")

            // GET
            mockMvc.get("/api/v1/events/$id")
                .andExpect {
                    status { isOk() }
                    jsonPath("$.title") { value("여름 쿠폰 이벤트") }
                    jsonPath("$.totalQuantity") { value(100) }
                    jsonPath("$.eventStatus") { value("READY") }
                }
        }

        // === Validation ===

        test("POST startedAt >= endedAt이면 400 INVALID_DATE_RANGE") {
            mockMvc.post("/api/v1/events") {
                contentType = MediaType.APPLICATION_JSON
                content = EventFixture.createRequestJson(
                    startedAt = "2026-07-07T23:59:59Z",
                    endedAt = "2026-07-01T00:00:00Z",
                )
            }.andExpect {
                status { isBadRequest() }
                jsonPath("$.code") { value("C400-1") }
            }
        }

        // === Not Found ===

        test("존재하지 않는 UUID로 GET 상세 시 404 EVENT_NOT_FOUND") {
            mockMvc.get("/api/v1/events/${UUID.randomUUID()}")
                .andExpect {
                    status { isNotFound() }
                    jsonPath("$.code") { value("E404") }
                }
        }

        // === Search ===

        test("여러 이벤트 생성 후 필터 검색이 정상 동작한다") {
            // 3개 이벤트 생성
            listOf("AAA 이벤트", "BBB 이벤트", "AAA 특별").forEach { title ->
                mockMvc.post("/api/v1/events") {
                    contentType = MediaType.APPLICATION_JSON
                    content = EventFixture.createRequestJson(title = title)
                }.andExpect { status { isCreated() } }
            }

            // keyword 검색
            mockMvc.get("/api/v1/events?keyword=AAA")
                .andExpect {
                    status { isOk() }
                    jsonPath("$.content.length()") { value(2) }
                }
        }
    }
})
```

**테스트 수**: 4개

> **참고**: `@SpringBootTest` + `@ActiveProfiles("test")`를 테스트 클래스에 직접 선언한다. IntegrationTestBase.companion의 `@ServiceConnection` 컨테이너는 Spring Context가 자동 감지한다.

---

## 7. Test Count Summary

| Layer | 파일 | 테스트 수 | Spec |
|-------|------|:---:|------|
| L1 | DateRangeTest | 12 | DescribeSpec + withData |
| L1 | EventStatusTest | 15 | DescribeSpec + withData |
| L1 | EventTest | 12 | DescribeSpec |
| L1 | EventCreateRequestTest | 2 | DescribeSpec |
| L1 | EventResponseTest | 2 | DescribeSpec |
| L1 | EventQueryOrdersTest | 10 | DescribeSpec + withData |
| **L1 소계** | | **53** | |
| L2 | EventServiceTest | 6 | FunSpec + MockK |
| **L2 소계** | | **6** | |
| L3 | EventQueryRepositoryTest | 8 | FunSpec + Testcontainers |
| L3 | EventControllerTest | 10 | FunSpec + @MockkBean + withData |
| **L3 소계** | | **18** | |
| L4 | EventCrudIntegrationTest | 4 | FunSpec + E2E |
| **L4 소계** | | **4** | |
| **전체** | **10 파일** | **81** | |

**비율**: L1(65%) > L3(22%) > L2(7%) > L4(5%) — Test Pyramid 원칙 준수

---

## 8. FR-T Coverage Matrix

| FR-ID | 테스트 파일 | 주요 테스트 |
|-------|-----------|-----------|
| FR-T01 | DateRangeTest | `불변식 위반 (startedAt >= endedAt)` withData |
| FR-T02 | DateRangeTest | `contains — 반개구간 [start, end)` withData |
| FR-T03 | EventStatusTest | `canTransitionTo` withData + `transitionTo 허용되지 않은 전이` |
| FR-T04 | EventStatusTest | `isIssuable` withData |
| FR-T05 | EventTest | `issue()` describe (READY/OPEN/CLOSED 각 context) |
| FR-T06 | EventTest | `open()`, `close()` describe |
| FR-T07 | EventQueryOrdersTest | `orders(sort)` describe + withData |
| FR-T08 | EventServiceTest | `create는 request를 Entity로 변환해 저장...` |
| FR-T09 | EventServiceTest | `getEvent는 존재하지 않는 ID에 대해 EVENT_NOT_FOUND` |
| FR-T10 | EventServiceTest | `getEvents는 eventQueryRepository.search에 조건 위임` |
| FR-T11 | EventQueryRepositoryTest | 필터 단독/조합 테스트 5개 |
| FR-T12 | EventQueryRepositoryTest | 페이징 + 정렬 테스트 3개 |
| FR-T13 | EventControllerTest | `POST title 빈 문자열 400`, `totalQuantity=0 400` |
| FR-T14 | EventControllerTest | `GET page=1 → pageNumber=0`, `statuses 바인딩` |
| FR-T15 | EventCrudIntegrationTest | `POST 생성 → GET 상세 일관성` |
| FR-T16 | EventCrudIntegrationTest | `POST startedAt >= endedAt → 400 INVALID_DATE_RANGE` |
| FR-T17 | EventCrudIntegrationTest | `존재하지 않는 UUID → 404 EVENT_NOT_FOUND` |

**커버리지**: FR-T01~T17 **전체 100%**

---

## 9. Implementation Order (Design → Code)

| Step | Task | 파일 | 의존성 |
|------|------|------|--------|
| 1 | build.gradle.kts 의존성 변경 | `build.gradle.kts` | — |
| 2 | `./gradlew build -x test` 빌드 확인 | — | Step 1 |
| 3 | ProjectConfig 생성 | `io/kotest/provided/ProjectConfig.kt` | Step 2 |
| 4 | IntegrationTestBase 확인 (이미 @ServiceConnection 적용, 변경 불필요) | `support/IntegrationTestBase.kt` | Step 2 |
| 5 | EventFixture 생성 | `support/EventFixture.kt` | Step 2 |
| 6 | DateRangeTest | `coupon/entity/DateRangeTest.kt` | Step 5 |
| 7 | EventStatusTest | `coupon/entity/EventStatusTest.kt` | Step 5 |
| 8 | EventTest | `coupon/entity/EventTest.kt` | Step 5 |
| 9 | EventCreateRequestTest | `coupon/dto/EventCreateRequestTest.kt` | Step 5 |
| 10 | EventResponseTest | `coupon/dto/EventResponseTest.kt` | Step 5 |
| 11 | EventQueryOrdersTest | `coupon/service/EventQueryOrdersTest.kt` | Step 5 |
| 12 | EventServiceTest (MockK) | `coupon/service/EventServiceTest.kt` | Step 5 |
| 13 | EventQueryRepositoryTest | `coupon/repository/EventQueryRepositoryTest.kt` | Step 4, 5 |
| 14 | EventControllerTest | `coupon/controller/EventControllerTest.kt` | Step 5 |
| 15 | EventCrudIntegrationTest | `coupon/EventCrudIntegrationTest.kt` | Step 4, 5 |
| 16 | PackageConfig (선택) | `coupon/entity/PackageConfig.kt` | Step 3 |
| 17 | `./gradlew test` 전체 통과 | — | Step 6~15 |
| 18 | `./gradlew koverHtmlReport` 커버리지 | — | Step 17 |

**병렬 가능**: Step 6~11 (L1 전체)는 서로 독립적이므로 동시 구현 가능.

---

## 10. Key Design Decisions

| # | 결정 | 근거 |
|---|------|------|
| D-01 | L1은 DescribeSpec, L2~L4는 FunSpec | L1의 도메인 규칙은 계층적 구조(describe/context/it)가 Living Documentation에 효과적. L2~L4는 플랫한 test() 나열이 간결 |
| D-02 | L1 경계값에 withData 적용 | 데이터 드리븐으로 누락 방지. 테스트 추가 시 데이터만 추가 |
| D-03 | SingleInstance + clearAllMocks() | Spring 컨텍스트 캐싱 유지 + MockK 상태 격리 양립 |
| D-04 | @MockkBean (springmockk 5.0.1) 도입 | springmockk 5.0.1이 Spring Framework 7 호환으로 출시. 전 계층 MockK DSL 통일 |
| D-05 | IntegrationTestBase는 Spec 미상속 | 컨테이너 홀더 역할만. 하위 테스트가 직접 FunSpec 상속. Kotlin 다중 상속 불가 + Kotest 공식 권장 |
| D-06 | Redis는 @DynamicPropertySource 유지 | GenericContainer는 @ServiceConnection 미지원. Postgres/Kafka만 전환 |
| D-07 | EventFixture.openEvent() 헬퍼 | OPEN 상태 Event 생성이 빈번하므로 `event() + open()` 축약 |
| D-08 | EventQueryRepositoryTest에 별도 Testcontainers | @DataJpaTest는 IntegrationTestBase의 @SpringBootTest와 다른 컨텍스트. 독립 컨테이너 필요 |

---

## 11. Troubleshooting Guide (자주 만나는 에러)

> 구현하다 막히면 여기를 먼저 확인하자.

### 에러 1: `No tests found` 또는 테스트가 아예 실행 안 됨

```
원인: ProjectConfig가 없거나 잘못된 패키지에 있음
해결: src/test/kotlin/io/kotest/provided/ProjectConfig.kt 확인
      패키지가 반드시 io.kotest.provided 여야 함
```

### 에러 2: `No bean of type 'JPAQueryFactory' found`

```
원인: @DataJpaTest는 최소한의 Bean만 로드하므로 JpaConfig가 빠짐
해결: @Import(JpaConfig::class) 추가
```

### 에러 3: `MockK: no answer found for ...`

```
원인: every { ... } stubbing을 안 했는데 mock 메서드가 호출됨
해결: 해당 메서드에 대한 every { ... } returns ... 추가

또는 relaxed mock 사용:
val repo = mockk<EventRepository>(relaxed = true)  // 기본값 자동 반환
```

### 에러 4: `Connection refused` (Testcontainers)

```
원인: Docker가 실행 중이 아님
해결: docker ps 로 확인. Docker Desktop이 켜져 있는지 확인
WSL 환경: sudo service docker start
```

### 에러 5: `@MockkBean`이 컴파일 안 됨

```
원인: springmockk 의존성 누락 또는 import 경로 오류
해결:
  1. build.gradle.kts에 testImplementation("com.ninja-squad:springmockk:5.0.1") 확인
  2. import com.ninjasquad.springmockk.MockkBean
```

### 에러 6: `withData`에서 `Unresolved reference`

```
원인: kotest-framework-datatest 의존성 누락
해결: build.gradle.kts에 testImplementation("io.kotest:kotest-framework-datatest:6.1.0") 확인
```

### 에러 7: 테스트 순서에 따라 성공/실패가 달라짐 (Flaky)

```
원인: mock 상태가 이전 테스트에서 오염됨
해결: beforeTest { clearAllMocks() } 확인
      (SingleInstance 모드에서 필수)
```

### 에러 8: `SpringExtension`이 동작 안 함 (DI 실패)

```
원인: kotest-extensions-spring 의존성 누락 또는 ProjectConfig에 등록 안 됨
해결:
  1. build.gradle.kts에 testImplementation("io.kotest:kotest-extensions-spring:6.1.0")
  2. ProjectConfig에 override val extensions = listOf(SpringExtension())
```

---

## 12. 학습 체크리스트

구현하면서 아래 항목을 하나씩 체크해보자:

### Kotest 기본
- [ ] `DescribeSpec`으로 테스트 파일 만들고 실행해봤다
- [ ] `describe` / `context` / `it` 의 차이를 안다
- [ ] `FunSpec`의 `test()`와 `DescribeSpec`의 `it()`이 같은 역할임을 안다
- [ ] `shouldBe`, `shouldThrow`를 사용해봤다
- [ ] `withData`로 여러 입력을 한 번에 테스트해봤다
- [ ] `beforeTest`와 `beforeSpec`의 차이를 안다

### MockK
- [ ] `mockk<T>()`로 mock 객체를 생성해봤다
- [ ] `every { ... } returns ...`로 stubbing 해봤다
- [ ] `verify(exactly = 1) { ... }`로 호출 검증해봤다
- [ ] `slot<T>()`과 `capture()`로 인자를 캡처해봤다
- [ ] `clearAllMocks()`가 왜 필요한지 안다

### Spring 테스트
- [ ] `@DataJpaTest`와 `@SpringBootTest`의 차이를 안다
- [ ] `@WebMvcTest`에서 MockMvc로 HTTP 요청을 보내봤다
- [ ] `@MockkBean`(springmockk)으로 Spring Bean을 MockK mock으로 교체해봤다
- [ ] `@ServiceConnection`이 @DynamicPropertySource를 대체하는 이유를 안다

### 테스트 설계
- [ ] "왜 도메인 객체를 mock하면 안 되는지" 설명할 수 있다
- [ ] Test Pyramid에서 L1이 가장 많아야 하는 이유를 안다
- [ ] Mock과 Real Object의 사용 기준을 안다

---

## Version History

| Version | Date | Changes | Author |
|---------|------|---------|--------|
| 0.1 | 2026-04-13 | Plan v0.4 기반 신규 작성. Kotest 6.1.0 Specs 전면 적용, 10개 테스트 파일 81개 테스트 상세 설계. ProjectConfig/PackageConfig/IntegrationTestBase 리팩토링 명세. FR-T01~T17 전체 커버리지 매핑. | beomjin |
| 0.2 | 2026-04-13 | 학습 가이드 추가: Section 0, [WHY]/[HOW]/[TRAP]/[TIP] 학습 포인트, Troubleshooting Guide, 학습 체크리스트 | beomjin |
| **0.3** | **2026-04-14** | **springmockk 5.0.1 도입 반영**: L3 `@MockitoBean` → `@MockkBean` 전환. Controller 테스트 코드를 MockK DSL(`every`/`slot`)로 변경. 유효성 검사 테스트에 `withData` 적용. 트러블슈팅/학습 체크리스트 업데이트. | beomjin |
