# Test Writing Guide — spring-event-lab

> **Tech Stack**: Kotest 6.1.0 + MockK 1.14.9 + springmockk 5.0.1  
> **Origin**: event-crud-test PDCA 아카이브 (2026-04-10 ~ 2026-04-14, Match Rate 95%)  
> **Last Updated**: 2026-04-16

---

## 1. Test Pyramid — 4계층 구조

```
        ╱╲
       ╱ L4 ╲         Integration (E2E)     — 최소한만, @SpringBootTest
      ╱──────╲
     ╱  L3    ╲       Slice                  — @DataJpaTest / @WebMvcTest
    ╱──────────╲
   ╱    L2      ╲     Service Unit (MockK)   — Spring 없음, 밀리초
  ╱──────────────╲
 ╱      L1        ╲   Domain Unit (순수)      — 가장 많고, 가장 빠르게
╱──────────────────╲
```

| Layer | Spec Style | Mock | Spring | 목적 |
|-------|-----------|------|--------|------|
| **L1** | `DescribeSpec` | 없음 | 없음 | Value Object, Entity, Enum 불변식/상태 전이 |
| **L2** | `FunSpec` | `mockk<T>()` | 없음 | Service orchestration 검증 |
| **L3** | `FunSpec` | `@MockkBean` | `@DataJpaTest` / `@WebMvcTest` | DB 쿼리, HTTP 요청/응답 |
| **L4** | `FunSpec` | 없음 | `@SpringBootTest` + Testcontainers | 전체 흐름 E2E |

### 핵심 원칙

- **아래가 클수록 좋다**: L1이 가장 많고 빨라야 한다
- **각 계층은 자기 책임만**: L1은 도메인 규칙, L2는 Service 로직, L3는 DB/HTTP
- **도메인 객체는 절대 mock하지 않는다**: Entity, Value Object, DTO, Enum은 real object

---

## 2. 기술 스택 & 의존성

### 2.1 핵심 라이브러리

| 라이브러리 | 버전 | 용도 |
|-----------|------|------|
| `kotest-runner-junit5` | 6.1.0 | Spec 러너 (FunSpec/DescribeSpec) |
| `kotest-assertions-core` | 6.1.0 | `shouldBe`, `shouldThrow<T>` 등 |
| `kotest-extensions-spring` | 6.1.0 | SpringExtension (DI 지원) |
| `kotest-framework-datatest` | 6.1.0 | `withData` 데이터 드리븐 테스팅 |
| `mockk` | 1.14.9 | Kotlin-native mock (final class 지원) |
| `springmockk` | 5.0.1 | `@MockkBean` — Spring Bean을 MockK mock으로 교체 |
| Power Assert | Kotlin 2.3 내장 | 실패 시 식의 중간값 시각화 (`-Xpower-assert`) |

### 2.2 build.gradle.kts 테스트 의존성

```kotlin
val koTestVersion = "6.1.0"

dependencies {
    // Kotest 6.1.0
    testImplementation("io.kotest:kotest-runner-junit5:$koTestVersion")
    testImplementation("io.kotest:kotest-assertions-core:$koTestVersion")
    testImplementation("io.kotest:kotest-extensions-spring:$koTestVersion")

    // MockK + SpringMockK
    testImplementation("io.mockk:mockk:1.14.9")
    testImplementation("com.ninja-squad:springmockk:5.0.1")
}

// Power Assert 활성화
kotlin {
    compilerOptions {
        freeCompilerArgs.addAll("-Xpower-assert")
    }
}
```

---

## 3. 전역 설정 — ProjectConfig

Kotest 6에서는 `@AutoScan`이 제거되었으므로 **반드시 정해진 위치**에 ProjectConfig가 있어야 한다.

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

| 설정 | 값 | 이유 |
|------|----|----|
| `SpringExtension` | 전역 등록 | L3/L4에서 개별 등록 불필요. L1/L2에서는 no-op |
| `SingleInstance` | Spec 인스턴스 1개 | Spring Context 캐싱과 호환 |
| `Sequential` | 순차 실행 | SingleInstance에서 병렬 실행하면 race condition |

---

## 4. Fixture 패턴

모든 테스트 데이터 생성은 `EventFixture` 한 곳을 통과한다. 기본값 오버라이드 방식으로 테스트별 차이만 표시.

**파일**: `src/test/kotlin/.../support/EventFixture.kt`

```kotlin
object EventFixture {
    val DEFAULT_START: Instant = Instant.parse("2026-07-01T00:00:00Z")
    val DEFAULT_END: Instant = Instant.parse("2026-07-07T23:59:59Z")

    fun dateRange(start: Instant = DEFAULT_START, end: Instant = DEFAULT_END) =
        DateRange(start, end)

    fun event(
        title: String = "여름 쿠폰 이벤트",
        totalQuantity: Int = 100,
        status: EventStatus = EventStatus.READY,
        period: DateRange = dateRange(),
    ) = Event(title, totalQuantity, status, period)

    fun openEvent(title: String = "진행중 이벤트", totalQuantity: Int = 100, period: DateRange = dateRange()): Event =
        event(title = title, totalQuantity = totalQuantity, status = EventStatus.READY, period = period)
            .also { it.open() }

    fun createRequest(...) = EventCreateRequest(...)
    fun response(event: Event = event()) = EventResponse.from(event)
    fun createRequestJson(...): String = """{ ... }""".trimIndent()
}
```

### Fixture 작성 원칙

- 새로운 도메인 추가 시 `{Domain}Fixture` 파일을 `support/` 패키지에 생성
- `object`로 선언 (인스턴스 1개)
- 모든 파라미터에 합리적인 기본값 제공
- 상태 전이가 필요한 헬퍼 (예: `openEvent()`)는 `also { it.open() }` 패턴

---

## 5. L1 — Domain Unit Test

> Mock 없음. Spring 없음. 순수 Kotlin 객체를 `new`해서 테스트.

### Spec: `DescribeSpec`

`describe`/`context`/`it` 계층 구조가 도메인 규칙을 **Living Documentation**으로 문서화한다.

```kotlin
class DateRangeTest : DescribeSpec({

    describe("DateRange 생성") {
        it("startedAt < endedAt이면 정상 생성된다") {
            val range = DateRange(EventFixture.DEFAULT_START, EventFixture.DEFAULT_END)
            range.startedAt shouldBe EventFixture.DEFAULT_START
        }
    }

    context("불변식 위반 (startedAt >= endedAt)") {
        withData(
            nameFn = { (s, e) -> "start=$s, end=$e" },
            EventFixture.DEFAULT_END to EventFixture.DEFAULT_START,  // 역순
            EventFixture.DEFAULT_START to EventFixture.DEFAULT_START, // 동일
        ) { (start, end) ->
            shouldThrow<BusinessException> { DateRange(start, end) }
                .errorCode shouldBe ErrorCode.INVALID_DATE_RANGE
        }
    }
})
```

### L1 작성 규칙

| 규칙 | 상세 |
|------|------|
| **경계값은 `withData`로** | enum 전체, 불변식 위반 케이스, contains 경계값 |
| **nameFn 필수** | 테스트 결과에서 어떤 데이터인지 구분 가능하게 |
| **describe = 대주제** | `describe("DateRange 생성")` |
| **context = 조건** | `context("불변식 위반")` |
| **it = 검증** | `it("INVALID_DATE_RANGE를 던진다")` — 실제 assertion은 여기서만 |
| **assertion 위치** | `describe`/`context` 블록 바로 아래에 assertion 금지. 반드시 `it()` 내부 |

### withData 패턴

```kotlin
// Pair 사용 — 구조 분해로 간결하게
withData(
    EventStatus.READY to false,
    EventStatus.OPEN to true,
    EventStatus.CLOSED to false,
) { (status, expected) ->
    status.isIssuable shouldBe expected
}

// Triple 사용 — 상태 전이 테이블
withData(
    Triple(EventStatus.READY, EventStatus.OPEN, true),
    Triple(EventStatus.READY, EventStatus.CLOSED, false),
) { (from, to, expected) ->
    from.canTransitionTo(to) shouldBe expected
}
```

---

## 6. L2 — Service Unit Test

> MockK로 Repository를 가짜로 대체. Spring 없이 밀리초 단위 검증.

### Spec: `FunSpec`

Service 테스트는 플랫한 시나리오이므로 `test()` 한 줄로 간결하게.

```kotlin
class EventServiceTest : FunSpec({

    val eventRepository = mockk<EventRepository>()
    val eventQueryRepository = mockk<EventQueryRepository>()
    val service = EventService(eventRepository, eventQueryRepository)

    beforeTest { clearAllMocks() }  // 필수: SingleInstance에서 mock 상태 격리

    test("create는 request를 Entity로 변환해 저장하고 EventResponse를 반환한다") {
        val request = EventFixture.createRequest()
        val saved = EventFixture.event()
        every { eventRepository.save(any<Event>()) } returns saved

        val response = service.create(request)

        response.title shouldBe saved.title
        verify(exactly = 1) { eventRepository.save(any<Event>()) }
    }

    test("getEvent는 존재하지 않는 ID에 대해 EVENT_NOT_FOUND를 던진다") {
        val id = UUID.randomUUID()
        every { eventRepository.findByIdOrNull(id) } returns null

        shouldThrow<BusinessException> { service.getEvent(id) }
            .errorCode shouldBe ErrorCode.EVENT_NOT_FOUND
    }
})
```

### MockK 핵심 DSL

```kotlin
// 1. Mock 생성
val repo = mockk<EventRepository>()

// 2. Stubbing
every { repo.findByIdOrNull(any()) } returns someEvent
every { repo.save(any<Event>()) } returns savedEvent

// 3. Verify (호출 여부)
verify(exactly = 1) { repo.save(any<Event>()) }

// 4. Slot (인자 캡처)
val slot = slot<Event>()
every { repo.save(capture(slot)) } answers { slot.captured }
service.create(request)
slot.captured.title shouldBe "기대한 제목"

// 5. clearAllMocks() — beforeTest에서 반드시 호출
beforeTest { clearAllMocks() }
```

### L2 작성 규칙

| 규칙 | 상세 |
|------|------|
| **`beforeTest { clearAllMocks() }` 필수** | SingleInstance에서 이전 테스트의 mock 설정이 오염 방지 |
| **Service는 직접 생성** | `val service = EventService(mockRepo, mockQueryRepo)` — Spring DI 안 씀 |
| **Repository만 mock** | Entity, DTO, Value Object는 real object |
| **slot으로 인자 검증** | Service가 request→Entity 변환을 정확히 했는지 확인 |

---

## 7. L3 — Slice Test

### 7.1 Repository Test (`@DataJpaTest`)

실제 PostgreSQL(Testcontainers)에서 QueryDSL 쿼리를 검증.

```kotlin
@DataJpaTest
@Import(JpaConfig::class)  // JPAQueryFactory Bean 등록 필수
class EventQueryRepositoryTest(
    private val eventQueryRepository: EventQueryRepository,
    private val entityManager: EntityManager,
) : FunSpec({

    beforeTest {
        // 각 테스트 전 데이터 삽입 (@DataJpaTest의 @Transactional 롤백과 호환)
        entityManager.persist(Event(...))
        entityManager.flush()
        entityManager.clear()
    }

    test("keyword='여름' — 제목에 '여름'이 포함된 이벤트만") {
        val result = eventQueryRepository.search(
            EventSearchCond(keyword = "여름"), PageRequest.of(0, 20)
        )
        result.content shouldHaveSize 1
    }
}) {
    companion object {
        @Container @ServiceConnection @JvmStatic
        val postgres = PostgreSQLContainer(DockerImageName.parse("postgres:18-alpine"))
    }
}
```

### 7.2 Controller Test (`@WebMvcTest` + `@MockkBean`)

전 계층 MockK DSL 통일 — springmockk 5.0.1의 `@MockkBean` 사용.

```kotlin
@WebMvcTest(EventController::class)
class EventControllerTest(
    private val mockMvc: MockMvc,
    @MockkBean private val eventService: EventService,
) : FunSpec({

    test("POST 정상 요청은 201을 반환한다") {
        every { eventService.create(any()) } returns EventFixture.response()

        mockMvc.post("/api/v1/events") {
            contentType = MediaType.APPLICATION_JSON
            content = EventFixture.createRequestJson()
        }.andExpect {
            status { isCreated() }
            jsonPath("$.title") { value("여름 쿠폰 이벤트") }
        }
    }

    // Bean Validation 실패 케이스 — withData로 데이터 드리븐
    context("POST 유효성 검사 실패 (400)") {
        data class BadRequestCase(val description: String, val payload: String)

        withData(
            nameFn = { it.description },
            BadRequestCase("title이 빈 문자열", EventFixture.createRequestJson(title = "")),
            BadRequestCase("totalQuantity가 0", EventFixture.createRequestJson(totalQuantity = 0)),
        ) { (_, payload) ->
            mockMvc.post("/api/v1/events") {
                contentType = MediaType.APPLICATION_JSON
                content = payload
            }.andExpect { status { isBadRequest() } }
        }
    }

    // slot으로 Pageable 변환 검증 (1-based → 0-based)
    test("GET page=1은 Pageable(pageNumber=0)로 Service에 전달한다") {
        val pageableSlot = slot<Pageable>()
        every { eventService.getEvents(any(), capture(pageableSlot)) } returns PageResponse.from(Page.empty())

        mockMvc.get("/api/v1/events?page=1&size=20")
            .andExpect { status { isOk() } }

        pageableSlot.captured.pageNumber shouldBe 0
    }
})
```

### L3 작성 규칙

| 규칙 | 상세 |
|------|------|
| **`@DataJpaTest` + `@Import(JpaConfig::class)`** | QueryDSL 사용 시 JPAQueryFactory Bean 필요 |
| **커스텀 Repository는 `@Import` 필수** | `@DataJpaTest`는 최소한의 Bean만 로드 |
| **`beforeTest`로 데이터 삽입** | `beforeSpec`은 @DataJpaTest 트랜잭션 롤백과 타이밍 불일치 |
| **`@MockkBean` import 경로** | `com.ninjasquad.springmockk.MockkBean` |
| **Kotest 생성자 주입** | `@Autowired lateinit var` 대신 생성자 파라미터 (Kotest 권장) |
| **Testcontainers companion** | `@Container @ServiceConnection @JvmStatic` 3개 어노테이션 조합 |

---

## 8. L4 — Integration Test

> 전체 Spring Context + 실제 DB + 실제 HTTP. 최소한의 E2E 시나리오만.

```kotlin
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
class EventCrudIntegrationTest(
    private val mockMvc: MockMvc,
) : FunSpec({

    test("POST 생성 → GET 상세로 동일한 데이터를 조회한다") {
        val createResult = mockMvc.post("/api/v1/events") {
            contentType = MediaType.APPLICATION_JSON
            content = EventFixture.createRequestJson()
        }.andExpect { status { isCreated() } }.andReturn()

        val id = JsonPath.read<String>(createResult.response.contentAsString, "$.id")

        mockMvc.get("/api/v1/events/$id")
            .andExpect {
                status { isOk() }
                jsonPath("$.title") { value("여름 쿠폰 이벤트") }
            }
    }
}) {
    companion object {
        @ServiceConnection @JvmStatic
        val postgres = PostgreSQLContainer(DockerImageName.parse("postgres:18-alpine")).apply { start() }

        @ServiceConnection(name = "redis") @JvmStatic
        val redis: GenericContainer<*> = GenericContainer(DockerImageName.parse("redis:7-alpine"))
            .withExposedPorts(6379).apply { start() }

        @ServiceConnection @JvmStatic
        val kafka = KafkaContainer(DockerImageName.parse("apache/kafka-native:latest")).apply { start() }
    }
}
```

### L4 작성 규칙

| 규칙 | 상세 |
|------|------|
| **IntegrationTestBase를 상속하지 않는다** | Spec 상속과 충돌. 각 테스트가 직접 FunSpec 상속 |
| **companion에 컨테이너 선언** | `@ServiceConnection` + `@JvmStatic`으로 Spring이 자동 감지 |
| **Redis는 `@ServiceConnection(name = "redis")`** | GenericContainer이므로 name 명시 필수 |
| **최소 시나리오만** | L1~L3에서 검증 완료된 것을 중복하지 않음 |
| **`@AutoConfigureMockMvc`** | `@SpringBootTest`에서 MockMvc 사용 시 필요 |

---

## 9. Kotest Assertion 치트시트

```kotlin
// 값 비교
actual shouldBe expected
actual shouldNotBe other

// 예외
shouldThrow<BusinessException> { someFunction() }
    .errorCode shouldBe ErrorCode.XXX

// 컬렉션
list shouldHaveSize 3
list shouldContain "item"
list.shouldBeEmpty()
list shouldContainExactlyInAnyOrder listOf("a", "b")

// null
value.shouldBeNull()
value.shouldNotBeNull()

// 문자열
str shouldContain "abc"
str shouldStartWith "hello"

// 비교
number shouldBeGreaterThan 5
number shouldBeLessThanOrEqualTo 10

// 정렬
list shouldBeSortedWith compareBy { it }
```

---

## 10. Mock vs Real Object 판단 기준

```
Mock으로 대체:
  ✅ Repository (DB 호출 → 느림)
  ✅ 외부 API 클라이언트
  ✅ 시간/난수 같은 비결정적 요소

Real Object 사용:
  ❌ Entity — 도메인 로직이 들어있으므로 mock하면 테스트 의미 상실
  ❌ Value Object (DateRange 등)
  ❌ DTO (EventCreateRequest, EventResponse)
  ❌ Enum (EventStatus)
```

---

## 11. 파일 구조 & 네이밍

```
src/test/kotlin/
├── io/kotest/provided/
│   └── ProjectConfig.kt              # 전역 설정 (필수)
└── com/beomjin/springeventlab/
    ├── coupon/
    │   ├── entity/
    │   │   ├── DateRangeTest.kt       # L1 DescribeSpec
    │   │   ├── EventStatusTest.kt     # L1 DescribeSpec + withData
    │   │   └── EventTest.kt           # L1 DescribeSpec
    │   ├── dto/
    │   │   ├── request/
    │   │   │   └── EventCreateRequestTest.kt  # L1 DescribeSpec
    │   │   └── response/
    │   │       └── EventResponseTest.kt       # L1 DescribeSpec
    │   ├── service/
    │   │   └── EventServiceTest.kt    # L2 FunSpec + MockK
    │   ├── repository/
    │   │   ├── EventQueryOrdersTest.kt         # L1 DescribeSpec + withData
    │   │   └── EventQueryRepositoryTest.kt     # L3 FunSpec + @DataJpaTest
    │   ├── controller/
    │   │   └── EventControllerTest.kt # L3 FunSpec + @WebMvcTest + @MockkBean
    │   └── EventCrudIntegrationTest.kt # L4 FunSpec + @SpringBootTest
    └── support/
        ├── IntegrationTestBase.kt     # 컨테이너 홀더 (Spec 상속 안 함)
        └── EventFixture.kt           # 테스트 팩토리
```

### 네이밍 규칙

| 항목 | 규칙 | 예시 |
|------|------|------|
| 테스트 파일 | `{클래스명}Test.kt` | `EventServiceTest.kt` |
| L4 통합 테스트 | `{Feature}IntegrationTest.kt` | `EventCrudIntegrationTest.kt` |
| Fixture | `{Domain}Fixture.kt` | `EventFixture.kt`, `CouponFixture.kt` |
| 테스트 위치 | 소스 코드와 동일 패키지 | `coupon/service/EventServiceTest.kt` |

---

## 12. Troubleshooting

### `No tests found`
ProjectConfig가 없거나 잘못된 패키지. `io.kotest.provided.ProjectConfig` 확인.

### `No bean of type 'JPAQueryFactory' found`
`@DataJpaTest`는 최소한의 Bean만 로드. `@Import(JpaConfig::class)` 추가.

### `MockK: no answer found for ...`
stubbing 안 된 mock 메서드 호출. `every { ... } returns ...` 추가.

### `Connection refused` (Testcontainers)
Docker 미실행. `docker ps`로 확인. WSL: `sudo service docker start`.

### `@MockkBean` 컴파일 오류
`import com.ninjasquad.springmockk.MockkBean` 확인. `com.ninja-squad:springmockk:5.0.1` 의존성 확인.

### `withData` Unresolved reference
`io.kotest:kotest-framework-datatest:6.1.0` 의존성 확인.

### Flaky 테스트 (순서 의존)
`beforeTest { clearAllMocks() }` 누락. SingleInstance 모드에서 필수.

### `SpringExtension` DI 실패
`kotest-extensions-spring` 의존성 + ProjectConfig에 `SpringExtension()` 등록 확인.

---

## 13. 성능 목표

| Layer | 전체 수행 시간 | 테스트 당 평균 |
|-------|:---:|:---:|
| L1 Domain | < 1초 | ~3ms |
| L2 Service | < 2초 | ~50ms |
| L3 Slice | < 30초 | ~133ms |
| L4 Integration | < 60초 | ~750ms |
| **전체** | **< 2분** | — |

---

## 14. 새 도메인 테스트 추가 시 체크리스트

1. [ ] `support/{Domain}Fixture.kt` 생성 (기본값 오버라이드 패턴)
2. [ ] **L1**: Entity/Value Object/Enum 테스트 (DescribeSpec)
   - [ ] 불변식 검증 (생성 시 예외)
   - [ ] 상태 전이 규칙 (withData)
   - [ ] 도메인 메서드 Happy/Unhappy path
3. [ ] **L1**: DTO 변환 테스트 (toEntity, from)
4. [ ] **L2**: Service 테스트 (FunSpec + MockK)
   - [ ] CRUD 각 메서드
   - [ ] Not Found 예외 경로
   - [ ] slot으로 인자 검증
5. [ ] **L3**: Repository 테스트 (@DataJpaTest + Testcontainers)
   - [ ] 필터 단독/조합/빈 필터
   - [ ] 정렬/페이징
6. [ ] **L3**: Controller 테스트 (@WebMvcTest + @MockkBean)
   - [ ] Bean Validation 400 (withData)
   - [ ] 성공 응답 JSON 구조
   - [ ] 파라미터 바인딩 (slot으로 검증)
7. [ ] **L4**: Integration 테스트 (최소 시나리오)
   - [ ] Happy Path E2E
   - [ ] 주요 에러 케이스 (400, 404)
8. [ ] `./gradlew test` 전체 통과 확인

---

## 15. 학습된 교훈 (Lessons Learned)

> event-crud-test PDCA Report에서 추출한 실전 교훈

### springmockk 버전 확인
Plan v0.4에서 "springmockk 4.0.2는 SB4 호환 불가"로 판단했으나 5.0.1이 이미 출시되어 있었다. **의존성 선정 시 최신 stable 버전을 Maven Central/GitHub Release에서 먼저 확인**할 것.

### beforeSpec vs beforeTest
`@DataJpaTest`와 `beforeSpec`은 트랜잭션 롤백 타이밍이 맞지 않아 상태 오염 발생. **Spring 테스트에서는 `beforeTest`를 사용**할 것.

### @DataJpaTest + 커스텀 Repository
`@DataJpaTest`는 매우 제한적이므로 커스텀 `@Repository`를 사용하려면 `@Import` 필수.

### IntegrationTestBase와 Spec 분리
Kotest에서 abstract base class에 Spec 상속을 조합하면 open 클래스 충돌. **IntegrationTestBase는 순수 컨테이너 홀더**로만, 각 테스트가 직접 Spec 상속.

### 경계값 테스트에서 최소값 사용
재고 소진 테스트에서 `totalQuantity=100`이면 100번 호출해야 한다. **`totalQuantity=1`로 빠르게 경계에 도달**.

---

## Appendix A. Kotest Spec 선택 가이드

| Spec | 구조 | 사용 시점 |
|------|------|---------|
| `DescribeSpec` | describe/context/it (BDD) | 도메인 규칙을 계층적으로 문서화 (L1) |
| `FunSpec` | test() (플랫) | Service, Slice, Integration (L2~L4) |
| `StringSpec` | "테스트명" { } | 초간단 유틸리티 테스트 |
| `BehaviorSpec` | given/when/then | BDD 스타일 선호 시 |
| `ShouldSpec` | should() | AssertJ 스타일 선호 시 |

**이 프로젝트 표준**: L1은 `DescribeSpec`, L2~L4는 `FunSpec`.

## Appendix B. 참고 문서

| 문서 | 위치 | 내용 |
|------|------|------|
| event-crud-test Plan | `docs/archive/2026-04/event-crud-test/plan.md` | 기술 선정 과정 (v0.1~v0.5 진화) |
| event-crud-test Design | `docs/archive/2026-04/event-crud-test/design.md` | 10개 테스트 클래스 상세 설계, 학습 가이드 |
| event-crud-test Analysis | `docs/archive/2026-04/event-crud-test/analysis.md` | Gap 분석 결과 (95% Match Rate) |
| event-crud-test Report | `docs/archive/2026-04/event-crud-test/report.md` | 완료 보고서, Lessons Learned |
| redis-stock Report | `docs/archive/2026-04/redis-stock/report.md` | Redis Lua 기반 재고 테스트 교훈 |
