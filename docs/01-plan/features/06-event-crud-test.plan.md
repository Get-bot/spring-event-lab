# Event CRUD Test Suite Planning Document

> **Summary**: 현재 구현된 `event-crud` 코드베이스에 대한 DDD 관점 테스트 스위트 작성 계획 (Kotest + MockK)
>
> **Project**: spring-event-lab
> **Version**: 0.0.1-SNAPSHOT
> **Author**: beomjin
> **Date**: 2026-04-10
> **Status**: Draft (v0.3)
> **Parent Feature**: [01-event-crud.plan.md](01-event-crud.plan.md) (v0.4)

---

## Executive Summary

| Perspective | Content |
|-------------|---------|
| **Problem** | `event-crud`가 DDD 리팩토링을 거쳐 도메인 로직(DateRange 불변식, EventStatus 전이 규칙, Event.issue 원인별 예외)과 QueryDSL 기반 다중 필터 검색이 구현됐지만, **검증하는 자동화 테스트가 없어 회귀(regression) 감지가 불가능**하다. |
| **Solution** | **Kotest(assertions) + MockK**를 도입해 Service 계층까지 Spring context 없이 빠른 순수 단위 테스트로 끌어올린다. Spring Boot 4 호환성을 위해 L3 Controller Slice는 `@MockitoBean`(Spring 네이티브)을 사용하는 **혼용 전략**. Domain Unit + Service Unit + Slice + Integration의 4계층. |
| **Function/UX Effect** | 개발자가 리팩토링 시 `./gradlew test`로 **회귀를 2분 내 감지**할 수 있다. Service 로직이 Spring context 로딩 없이 밀리초 단위로 검증되어 TDD 사이클이 짧아지고, 도메인 불변식 위반이 프로덕션으로 나가지 않는다. |
| **Core Value** | DDD 도메인 지식이 **Kotest의 Kotlin-native DSL**(`shouldBe`, `shouldThrow<T>`)로 Living Documentation화되고, **MockK**가 Kotlin final class를 그대로 mock해 `allopen` 플러그인이나 `open class` 변경 없이 깔끔한 단위 테스트가 가능하다. |

---

## 1. Overview

### 1.1 Purpose

`event-crud` feature의 모든 계층을 **Test Pyramid 원칙**에 따라 검증한다. 단순 커버리지 숫자보다 **도메인 불변식 · 유스케이스 경로 · 레이어 경계**에 집중한다.

### 1.2 Why Kotest + MockK (결정 사유)

초안(v0.1)에서 "Spring 기본 스타터로만"을 고집했으나, Kotlin 프로젝트의 현실적 제약과 **DSL 표현력**을 반영해 **v0.3에서 Kotest(assertions) + MockK**로 확정한다.

#### 1.2.1 MockK 도입 사유

| 이슈 | Mockito만 사용할 때 | MockK 사용할 때 |
|------|--------------------|----------------|
| **Kotlin final class** | `allopen` 플러그인 또는 `open class` 강제 | **그대로 mock 가능** |
| **Service 단위 테스트** | `@SpringBootTest` 또는 수동 stub | `mockk<EventRepository>()` 한 줄 |
| **테스트 DSL** | `when(repo.findById(any())).thenReturn(...)` | `every { repo.findByIdOrNull(id) } returns ...` |
| **Verify DSL** | `verify(repo, times(1)).save(any())` | `verify(exactly = 1) { repo.save(any()) }` |
| **Slot / Capture** | `ArgumentCaptor` boilerplate | `val slot = slot<T>(); every { f(capture(slot)) } ...` |

#### 1.2.2 Kotest (Assertions) 도입 사유

Kotest specs(`FunSpec`, `DescribeSpec`)까지 전면 도입하면 러닝 커브가 크지만, **`kotest-assertions-core`는 JUnit 5 `@Test`와 같이 혼용 가능**하다. 그래서 **Kotest specs는 L1 Domain에만 적용**하고, 나머지는 JUnit 5 + Kotest assertions 혼용.

| 표현 | JUnit 5 / AssertJ | Kotest Assertions |
|------|-------------------|------------------|
| 값 비교 | `assertEquals(expected, actual)` / `assertThat(actual).isEqualTo(expected)` | `actual shouldBe expected` |
| 예외 검증 | `assertThrows<T> { ... }` | `shouldThrow<T> { ... }` |
| 컬렉션 | `assertThat(list).hasSize(3)` | `list shouldHaveSize 3` |
| null 체크 | `assertNull(value)` | `value.shouldBeNull()` |
| 문자열 포함 | `assertThat(s).contains("abc")` | `s shouldContain "abc"` |

Kotest DSL이 **훨씬 Kotlin스럽고 읽기 쉽다**. 이게 Living Documentation의 품질을 올린다.

#### 1.2.3 springmockk 도입 **보류** (Spring Boot 4 호환성)

검토 결과 `springmockk:4.0.2`는 내부적으로 **Spring Boot 3.0 dependencies + Kotlin 1.7.21 stdlib + MockK 1.13.3**을 기반으로 빌드됨. Spring Boot 4.0.5 + Kotlin 2.3.20 환경에서 **런타임 호환성 리스크**가 있어 **도입 보류**.

**대체 전략**: L3 Controller Slice는 Spring Boot 4 네이티브 `@MockitoBean`을 사용. Mockito 5의 InlineMockMaker가 기본이라 Kotlin final class도 지원. L3에서만 Mockito DSL이 섞이지만, 테스트 수가 1~5개 수준이라 일관성 손실은 작음.

**결정**: **Kotest(assertions) + MockK + `@MockitoBean`(L3 한정) + Kotest specs(L1 선택적)**. 이렇게 하면 호환성 리스크를 0으로 낮추면서도 Kotlin-native DSL의 장점을 대부분 확보한다.

### 1.3 Testing Philosophy (DDD + Kotest + MockK)

- **Domain tests first**: Value Object, Entity, Enum의 불변식과 상태 전이는 **가장 먼저**, **가장 많이**, **가장 빠르게** 검증 — mock 없이 순수 객체
- **Service as pure unit**: `EventService`는 MockK로 `EventRepository`, `EventQueryRepository`를 대체해 **Spring context 없이** 밀리초 단위 테스트
- **Living Documentation**: 테스트 케이스 이름이 도메인 규칙을 한국어로 설명 + Kotest DSL로 기대값 표현
- **Don't mock what you don't own**: Repository/Entity는 절대 mock하지 않고 Testcontainers real DB 사용 (QueryDSL/JPA 동작 검증이 목적)
- **Don't over-mock**: Value Object, Enum은 mock 불가/불필요 — real object
- **L3는 Spring 네이티브**: `@MockitoBean`으로 호환성 안전성 우선 (DSL 일관성 < 호환성)
- **Test Pyramid 4-layer**: Domain Unit > Service Unit(MockK) > Slice(Repo + Controller) > Integration(E2E)

### 1.4 Scope Boundary

```
┌─ Layer 1: Domain Unit ─────────────────────────────────────┐
│  DateRange, EventStatus, Event (pure domain, NO mocks)     │  ← 최다, 최고속
│  EventQuery.orders (순수 함수)                              │
│  EventCreateRequest.toEntity(), EventResponse.from()        │
└─────────────────────────────────────────────────────────────┘
┌─ Layer 2: Service Unit (Mockk) ────────────────────────────┐
│  EventService (repository를 Mockk로 대체, no Spring)       │  ← NEW in v0.2
│  - create/getEvent/getEvents 로직 격리 검증                 │
└─────────────────────────────────────────────────────────────┘
┌─ Layer 3: Slice Tests ─────────────────────────────────────┐
│  EventQueryRepository (@DataJpaTest + Testcontainers)      │
│  EventController (@WebMvcTest + @MockkBean EventService)   │  ← springmockk
└─────────────────────────────────────────────────────────────┘
┌─ Layer 4: Integration ─────────────────────────────────────┐
│  End-to-end CRUD flow (@SpringBootTest + Testcontainers)   │  ← 최소, 핵심만
└─────────────────────────────────────────────────────────────┘
```

---

## 2. Scope

### 2.1 In Scope

#### Layer 1 — Domain Unit (의존성 0, Mock 없음)
- [ ] `DateRangeTest` — 불변식 검증, `isUpcoming/isOngoing/isEnded/contains` 경계값
- [ ] `EventStatusTest` — `isIssuable` 프로퍼티, `canTransitionTo`, `transitionTo` Happy/예외
- [ ] `EventTest` — 생성 시 불변식(DateRange 경유), `remainingQuantity`, `isIssuable`, `issue`/`open`/`close`
- [ ] `EventCreateRequestTest` — `toEntity()` 체인 검증 (null-safety `!!` 포함)
- [ ] `EventResponseTest` — `from(entity)` 매핑 정확성
- [ ] `EventQueryOrdersTest` — `orders(sort)` 화이트리스트/빈 정렬 fallback (순수 함수)

#### Layer 2 — Service Unit (Mockk 기반, NO Spring)
- [ ] `EventServiceTest`
  - `create(request)` — repository.save 호출, EventResponse 반환 검증
  - `getEvent(id)` Happy — findByIdOrNull 반환값 매핑
  - `getEvent(id)` Not Found — `EVENT_NOT_FOUND` 던짐
  - `getEvents(cond, pageable)` — eventQueryRepository.search 호출, 결과 매핑 + `PageResponse.from` 변환
  - **ArgumentCaptor/slot**으로 repository에 전달된 인자 검증

#### Layer 3 — Slice Tests
- [ ] `EventQueryRepositoryTest` (`@DataJpaTest` + `@Import(JpaConfig::class)` + Testcontainers Postgres)
  - 필터 단독/조합, 다중 정렬, 1-based 페이징, lazy count 동작
- [ ] `EventControllerTest` (`@WebMvcTest(EventController::class)` + `@MockkBean EventService`)
  - Bean Validation 실패 → `INVALID_INPUT`
  - `@ParameterObject` 바인딩 (`?statuses=OPEN&statuses=READY`)
  - `@PageableDefault` 기본값 + 1-based → 0-based 변환 검증 (Mockk `slot`)
  - 성공 응답 JSON 구조 검증

#### Layer 4 — Integration Tests (최소)
- [ ] `EventCrudIntegrationTest` (`IntegrationTestBase` 상속)
  - POST 생성 → GET 상세 → GET 검색(필터 조합) → 404 시나리오
  - `DateRange` 불변식이 HTTP 400(`INVALID_DATE_RANGE`)으로 돌아오는지 확인
  - 실제 Flyway 마이그레이션 통합 검증

### 2.2 Out of Scope

- **성능/부하 테스트**: redis-stock feature에서 진행
- **보안 테스트**: 인증/인가 미적용 단계
- **Kafka 이벤트 테스트**: kafka-consumer feature
- **100% 라인 커버리지 목표**: 의미 있는 경로만 집중, 커버리지는 결과지표
- **E2E 회귀 전체**: QA 자동화는 별도 feature
- **Mockito 유지/병행**: Mockk로 일원화 (이미 Spring Boot test starter에 Mockito가 포함되지만 사용하지 않음)

---

## 3. Requirements

### 3.1 Functional Requirements

| ID | Requirement | Priority | Layer |
|----|-------------|----------|-------|
| FR-T01 | DateRange 불변식(`startedAt < endedAt`) 위반 시 `INVALID_DATE_RANGE` 던짐 | High | L1 Domain |
| FR-T02 | DateRange 경계값 — `contains/isOngoing`는 `[start, end)` 반개구간 | High | L1 Domain |
| FR-T03 | EventStatus 전이 허용/차단 — READY→OPEN→CLOSED, 역전이/스킵 차단 | High | L1 Domain |
| FR-T04 | EventStatus.isIssuable — OPEN만 true | High | L1 Domain |
| FR-T05 | Event.issue() — status 위반 시 `EVENT_NOT_OPEN`, 재고 부족 시 `EVENT_OUT_OF_STOCK` | High | L1 Domain |
| FR-T06 | Event.open()/close() 성공/실패 경로 | High | L1 Domain |
| FR-T07 | EventQuery.orders 화이트리스트/빈 정렬 기본값 | High | L1 Domain |
| FR-T08 | **EventService.create** — repository.save 호출 + 반환값 매핑 (Mockk) | High | L2 Service |
| FR-T09 | **EventService.getEvent** — Not found 시 `EVENT_NOT_FOUND` (Mockk) | High | L2 Service |
| FR-T10 | **EventService.getEvents** — eventQueryRepository.search 위임 + PageResponse 매핑 (Mockk) | High | L2 Service |
| FR-T11 | EventQueryRepository — 필터 단독/조합/빈 필터 | High | L3 Slice |
| FR-T12 | EventQueryRepository — 1-based 페이지, 화이트리스트 정렬 | High | L3 Slice |
| FR-T13 | EventController — Bean Validation 400 `INVALID_INPUT` | High | L3 Slice |
| FR-T14 | EventController — `@ParameterObject` 바인딩 + 1→0 페이지 변환 (slot) | High | L3 Slice |
| FR-T15 | Integration E2E — POST/GET 상세/GET 검색 Happy Path | High | L4 E2E |
| FR-T16 | Integration — `INVALID_DATE_RANGE` HTTP 400 변환 | High | L4 E2E |
| FR-T17 | Integration — 존재하지 않는 UUID → 404 `EVENT_NOT_FOUND` | High | L4 E2E |

### 3.2 Non-Functional Requirements

| Category | Criteria | Target |
|----------|----------|--------|
| Speed (Layer 1) | Domain unit 전체 수행 | **< 1초** (Spring context X) |
| Speed (Layer 2) | Service unit 전체 수행 (Mockk) | **< 2초** (Spring context X) |
| Speed (Layer 3) | Slice test 전체 수행 | < 30초 (컨테이너 캐싱 포함) |
| Speed (Layer 4) | Integration test 전체 | < 60초 |
| Speed (전체) | `./gradlew test` 전체 | **< 2분** |
| Isolation | 각 테스트 독립 실행 (mock 재생성, @Transactional rollback) | 의존성 순서 X |
| Readability | 테스트 이름이 한국어로 도메인 규칙 설명 | `fun \`DateRange는 시작이 종료보다 이후면 INVALID_DATE_RANGE를 던진다\`()` |
| Maintainability | 테스트 픽스처 중앙화 (`EventFixture`) | 중복 제거 |
| Stability | 시간 기반 테스트는 고정 시각 주입 | `periodMatches(period, now)` 사용 |

---

## 4. Testing Strategy

### 4.1 Test Pyramid 배분 (v0.2 — Mockk 반영)

| Layer | Tool | 비중 | 실행 시간 |
|-------|------|:---:|:---:|
| **L1 Domain Unit** | JUnit 5 + kotlin-test | ~45% | < 1초 |
| **L2 Service Unit** | JUnit 5 + **Mockk** | ~20% | < 2초 |
| **L3 Slice** | `@DataJpaTest` / `@WebMvcTest` + Testcontainers + **springmockk** | ~25% | ~30초 |
| **L4 Integration** | `@SpringBootTest` + `IntegrationTestBase` | ~10% | ~60초 |

### 4.2 New Test Dependencies (build.gradle.kts에 추가)

```kotlin
dependencies {
    // ... 기존 의존성 유지

    // Kotest (Kotlin-native assertions — L1~L4 공통)
    testImplementation("io.kotest:kotest-runner-junit5:5.9.1")
    testImplementation("io.kotest:kotest-assertions-core:5.9.1")

    // MockK (Kotlin-native mocking — L2 Service unit 주력)
    testImplementation("io.mockk:mockk:1.14.3")
}
```

**Maven Central 조사 결과 (버전 선택 근거)**:
- **Kotest `5.9.1`**: 최신 안정판. 6.x는 Milestone(M1~M4)이라 불안정
- **MockK `1.14.3`**: 최신 안정판, Kotlin 2.1.20 이상 요구 — 우리 프로젝트 Kotlin 2.3.20과 호환
- **springmockk 도입 보류**: `4.0.2`가 Maven Central 최신이지만 `spring-boot-dependencies:3.0.0` 기반이라 Spring Boot 4 호환성 리스크. `@MockitoBean`(Spring Boot 4 네이티브)으로 대체.

**제외한 의존성 (과공학 회피)**:
- ~~`kotest-property`~~ — Property-based testing은 이 CRUD 규모에 과함. 필요 시 추후 추가
- ~~`kotlinx-coroutines-test`~~ — 현재 coroutines 미사용
- ~~`spring-boot-starter-test`~~ — Spring Boot 4는 모듈러 스타터 사용 중(`-webmvc-test`, `-data-jpa-test` 등) 이미 포함
- ~~`kotlin-test-junit5:2.0.0`~~ — 버전 오기, 이미 `testImplementation("org.jetbrains.kotlin:kotlin-test-junit5")` 존재
- ~~`id("org.jetbrains.kotlin.plugin.junit")`~~ — 존재하지 않는 플러그인. JUnit 5는 `useJUnitPlatform()`으로 활성화 중
- ~~`springmockk`~~ — Spring Boot 4 호환성 리스크. `@MockitoBean`으로 대체

### 4.3 Layer별 책임

#### 4.3.1 L1 — Domain Unit (pure)

| 대상 | 검증 포인트 | Mock 사용 |
|------|------------|:--------:|
| `DateRange` | 불변식, 경계값(start 포함/end 제외), 메서드 계산 | ❌ |
| `EventStatus` | `isIssuable`, 전이 규칙 (allowedTransitions) | ❌ |
| `Event` | DateRange 경유 불변식, 도메인 로직 메서드 | ❌ |
| `EventQuery.orders` | Sort 입력 → OrderSpecifier 배열 (화이트리스트) | ❌ |
| `EventCreateRequest.toEntity()` | DTO → DateRange/Event 체인, null-safety | ❌ |
| `EventResponse.from()` | Entity 필드 복사, `remainingQuantity` 계산 | ❌ |

**도구**: JUnit 5 + `kotlin-test-junit5` + AssertJ
**원칙**: Value Object와 Entity는 real object로 테스트. Mock은 금지(이유: 도메인 불변식이 바이패스됨)

#### 4.3.2 L2 — Service Unit (MockK + Kotest Assertions)

**`EventServiceTest`**:

```kotlin
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.matchers.collections.shouldHaveSize
import io.kotest.matchers.shouldBe
import io.mockk.every
import io.mockk.mockk
import io.mockk.slot
import io.mockk.verify

class EventServiceTest {

    private val eventRepository: EventRepository = mockk()
    private val eventQueryRepository: EventQueryRepository = mockk()
    private val service = EventService(eventRepository, eventQueryRepository)

    @Test
    fun `create는 request를 Entity로 변환해 저장하고 EventResponse를 반환한다`() {
        // given
        val request = EventFixture.createRequest()
        val saved = EventFixture.event()
        every { eventRepository.save(any()) } returns saved

        // when
        val response = service.create(request)

        // then
        response.title shouldBe saved.title
        verify(exactly = 1) { eventRepository.save(any<Event>()) }
    }

    @Test
    fun `getEvent는 존재하지 않는 ID에 대해 EVENT_NOT_FOUND를 던진다`() {
        val id = UUID.randomUUID()
        every { eventRepository.findByIdOrNull(id) } returns null

        val ex = shouldThrow<BusinessException> { service.getEvent(id) }
        ex.errorCode shouldBe ErrorCode.EVENT_NOT_FOUND
    }

    @Test
    fun `getEvents는 eventQueryRepository에 검색조건과 Pageable을 그대로 위임한다`() {
        val cond = EventSearchCond(keyword = "여름")
        val pageable = PageRequest.of(0, 20)
        val page: Page<Event> = PageImpl(listOf(EventFixture.event()), pageable, 1)

        val slotCond = slot<EventSearchCond>()
        val slotPageable = slot<Pageable>()
        every { eventQueryRepository.search(capture(slotCond), capture(slotPageable)) } returns page

        val result = service.getEvents(cond, pageable)

        slotCond.captured.keyword shouldBe "여름"
        slotPageable.captured.pageSize shouldBe 20
        result.content shouldHaveSize 1
    }
}
```

**MockK 핵심 DSL**:
- `mockk<T>()` — interface/class mock (Kotlin final 지원)
- `every { ... } returns ...` — stubbing
- `verify(exactly = N) { ... }` — invocation 검증
- `slot<T>()` + `capture(slot)` — ArgumentCaptor 대체

**Kotest Assertions 핵심 DSL**:
- `actual shouldBe expected`
- `shouldThrow<T> { ... }` → throwable 반환 (`ex.errorCode shouldBe ...`)
- `collection shouldHaveSize N`
- `value.shouldBeNull()`, `value.shouldNotBeNull()`

**원칙**: Service는 **thin orchestration layer**이므로 테스트도 가볍다. Repository는 mock, Entity/DTO는 real object.

#### 4.3.3 L3 — Slice Tests

**`EventQueryRepositoryTest`** (`@DataJpaTest` + Testcontainers):
- JPA/QueryDSL만 로드 (Controller/Service 제외)
- `@Import(JpaConfig::class)`로 `JPAQueryFactory` 주입
- 필터 단독/조합/복합, 정렬 화이트리스트, lazy count 검증
- 시간 기반 테스트는 `periodMatches(period, fixedNow)`로 고정

**`EventControllerTest`** (`@WebMvcTest` + `@MockitoBean` + Kotest assertions):

> Spring Boot 4 호환성을 위해 L3만 `@MockitoBean` (Spring 네이티브) 사용. DSL은 Kotest assertions + BDDMockito 혼용.

```kotlin
import io.kotest.matchers.shouldBe
import org.mockito.kotlin.*
import org.springframework.test.context.bean.override.mockito.MockitoBean

@WebMvcTest(EventController::class)
class EventControllerTest {

    @Autowired lateinit var mockMvc: MockMvc
    @MockitoBean lateinit var eventService: EventService

    @Test
    fun `POST events 정상 요청은 201과 EventResponse를 반환한다`() {
        given(eventService.create(any())).willReturn(EventFixture.response())

        mockMvc.post("/api/v1/events") {
            contentType = MediaType.APPLICATION_JSON
            content = EventFixture.createRequestJson()
        }.andExpect {
            status { isCreated() }
            jsonPath("$.id") { exists() }
        }
    }

    @Test
    fun `GET events는 1-based page 파라미터를 0-based Pageable로 Service에 전달한다`() {
        val captor = argumentCaptor<Pageable>()
        given(eventService.getEvents(any(), captor.capture()))
            .willReturn(PageResponse.from(Page.empty()))

        mockMvc.get("/api/v1/events?page=1&size=20")
            .andExpect { status { isOk() } }

        captor.firstValue.pageNumber shouldBe 0   // Kotest assertion
        captor.firstValue.pageSize shouldBe 20
    }
}
```

**주의**:
- `mockito-kotlin`이 Spring Boot test starter에 자동 포함되어 `given`, `any()`, `argumentCaptor<T>()` 등 사용 가능
- 어설션은 **Kotest(`shouldBe`)** 사용해 L2와 일관성 유지
- `@MockitoBean`은 Spring Boot 3.4+ 네이티브 API

#### 4.3.4 L4 — Integration (최소)

**`EventCrudIntegrationTest`** (`IntegrationTestBase` 상속):
- 실제 Testcontainers Postgres + Flyway
- 최소 시나리오만:
  1. POST 생성 → 201, 반환 id로 GET 상세 → 200
  2. 여러 이벤트 생성 → 검색 필터 조합 동작
  3. 존재하지 않는 UUID → 404 `EVENT_NOT_FOUND`
  4. POST `startedAt > endedAt` → 400 `INVALID_DATE_RANGE`
- `@Transactional` + rollback 격리

### 4.4 Testing Tools (최종)

| 도구 | 용도 | 상태 |
|------|------|:---:|
| JUnit 5 | 테스트 러너 | ✅ 기존 |
| `kotlin-test-junit5` | Kotlin test helpers | ✅ 기존 |
| **Kotest `5.9.1`** | **Kotlin-native assertions (`shouldBe`, `shouldThrow`)** | ⏳ **신규** |
| **MockK `1.14.3`** | **Kotlin-native mock (L1~L2)** | ⏳ **신규** |
| `@MockitoBean` | L3 Controller slice (Spring Boot 4 네이티브) | ✅ 기존 (starter에 포함) |
| `mockito-kotlin` | Kotlin용 Mockito DSL (`given`, `any()`, `argumentCaptor`) | ✅ 기존 (starter에 포함) |
| MockMvc | HTTP 레이어 | ✅ 기존 |
| Testcontainers (postgres/redis/kafka) | 실 DB/인프라 | ✅ 기존 |

### 4.5 Risk: Spring Boot 4 호환성 (확인 완료)

| 라이브러리 | 버전 | Spring Boot 4 호환 | 확인 방법 |
|-----------|------|:---:|---------|
| Kotest | `5.9.1` | ✅ (프레임워크 독립) | 어설션은 AssertionError만 던지므로 프레임워크 의존 X |
| MockK | `1.14.3` | ✅ | Kotlin 2.1.20+ 요구, 우리 2.3.20 OK |
| `@MockitoBean` | Spring Boot 3.4+ | ✅ | Spring Boot 4 네이티브 |
| ~~springmockk~~ | ~~4.0.2~~ | ❌ 미도입 | Spring Boot 3.0 기반, 4.x 미지원 |

### 4.6 Test Fixture 전략

```kotlin
// test/kotlin/.../support/EventFixture.kt
object EventFixture {

    private val DEFAULT_START: Instant = Instant.parse("2026-07-01T00:00:00Z")
    private val DEFAULT_END: Instant = Instant.parse("2026-07-07T23:59:59Z")

    fun dateRange(
        start: Instant = DEFAULT_START,
        end: Instant = DEFAULT_END,
    ) = DateRange(start, end)

    fun event(
        title: String = "여름 쿠폰 이벤트",
        totalQuantity: Int = 100,
        status: EventStatus = EventStatus.READY,
        period: DateRange = dateRange(),
    ): Event = Event(title, totalQuantity, status, period)

    fun createRequest(
        title: String? = "여름 쿠폰 이벤트",
        totalQuantity: Int? = 100,
        startedAt: Instant? = DEFAULT_START,
        endedAt: Instant? = DEFAULT_END,
    ) = EventCreateRequest(title, totalQuantity, startedAt, endedAt)

    fun response(
        event: Event = event(),
    ): EventResponse = EventResponse.from(event)

    fun createRequestJson(): String = """
        {
          "title": "여름 쿠폰 이벤트",
          "totalQuantity": 100,
          "startedAt": "2026-07-01T00:00:00Z",
          "endedAt": "2026-07-07T23:59:59Z"
        }
    """.trimIndent()
}
```

**원칙**: 모든 Event/DateRange/DTO 생성이 한 곳을 지나감. 기본값 오버라이드 방식으로 테스트별 차이만 표시.

---

## 5. Test Scenarios (상세)

### 5.1 L1 — DateRange (Unit)

| # | 시나리오 | 기대 결과 |
|---|---------|---------|
| 1 | `startedAt < endedAt` 정상 | 성공 |
| 2 | `startedAt == endedAt` | `INVALID_DATE_RANGE` |
| 3 | `startedAt > endedAt` | `INVALID_DATE_RANGE` |
| 4 | `contains(start)` | true |
| 5 | `contains(end)` | **false** (반개구간) |
| 6 | `contains(start - 1ms)` | false |
| 7 | `contains((start+end)/2)` | true |
| 8 | `isUpcoming(before start)` | true |
| 9 | `isOngoing(at start)` | true |
| 10 | `isEnded(at end)` | true |

### 5.2 L1 — EventStatus (Unit)

| # | 시나리오 | 기대 결과 |
|---|---------|---------|
| 1 | `READY.isIssuable` | false |
| 2 | `OPEN.isIssuable` | true |
| 3 | `CLOSED.isIssuable` | false |
| 4 | `READY.canTransitionTo(OPEN)` | true |
| 5 | `READY.canTransitionTo(CLOSED)` | false (스킵 금지) |
| 6 | `OPEN.canTransitionTo(CLOSED)` | true |
| 7 | `OPEN.canTransitionTo(READY)` | false (역전이) |
| 8 | `CLOSED.canTransitionTo(*)` | 모두 false |
| 9 | `transitionTo` 실패 시 예외 | `EVENT_INVALID_STATUS_TRANSITION` |

### 5.3 L1 — Event (Unit)

| # | 시나리오 | 기대 결과 |
|---|---------|---------|
| 1 | 정상 생성 | `issuedQuantity=0`, `remainingQuantity=100` |
| 2 | `issue()` with READY | `EVENT_NOT_OPEN` |
| 3 | `issue()` OPEN + qty=0 | `EVENT_OUT_OF_STOCK` |
| 4 | `issue()` 정상 | `issuedQuantity++` |
| 5 | `open()` from READY | `OPEN` |
| 6 | `open()` from OPEN | `EVENT_INVALID_STATUS_TRANSITION` |
| 7 | `close()` from OPEN | `CLOSED` |
| 8 | `isIssuable()` READY+qty>0 | false |
| 9 | `isIssuable()` OPEN+qty=0 | false |
| 10 | `isIssuable()` OPEN+qty>0 | true |

### 5.4 L1 — EventQuery.orders (Unit)

| # | 시나리오 | 기대 결과 |
|---|---------|---------|
| 1 | `Sort.unsorted()` | `[createdAt.desc()]` fallback |
| 2 | `Sort.by(ASC, "title")` | `[title.asc()]` |
| 3 | `Sort.by("startedAt")` | `period.startedAt` 매핑 |
| 4 | 다중 정렬 | 2개 OrderSpecifier |
| 5 | 화이트리스트 외 `password` | 무시 + fallback |
| 6 | 일부 유효 + 일부 무효 | 유효한 것만 |

### 5.5 L2 — EventService (Mockk)

| # | 시나리오 | Mock 설정 | 기대 결과 |
|---|---------|----------|---------|
| 1 | `create(request)` | `every { repo.save(any()) } returns event` | EventResponse 반환, save 1회 호출 |
| 2 | `create`에 전달된 Entity 검증 | `val slot = slot<Event>(); every { repo.save(capture(slot)) } ...` | slot.captured.title 등 필드 확인 |
| 3 | `getEvent(id)` Happy | `every { repo.findByIdOrNull(id) } returns event` | EventResponse 반환 |
| 4 | `getEvent(id)` Not Found | `every { repo.findByIdOrNull(id) } returns null` | `EVENT_NOT_FOUND` throw |
| 5 | `getEvents(cond, pageable)` | `every { queryRepo.search(cond, pageable) } returns PageImpl(...)` | PageResponse 매핑 |
| 6 | `getEvents`에 전달된 cond/pageable | `slot<EventSearchCond>()` + `slot<Pageable>()` | 위임 파라미터 검증 |

### 5.6 L3 — EventQueryRepository (`@DataJpaTest` + Testcontainers)

| # | 시나리오 | 기대 결과 |
|---|---------|---------|
| 1 | 필터 없음 | 전체 + 기본 정렬 |
| 2 | `keyword="여름"` (TITLE) | 제목 필터링 |
| 3 | `statuses=[OPEN, READY]` | 해당 상태만 |
| 4 | `period=ONGOING` | 현재 시각 기준 진행 중인 것만 |
| 5 | `createdFrom/To` | 생성일 범위 |
| 6 | `hasRemainingStock=true` | `issuedQuantity < totalQuantity` |
| 7 | 조합 필터 | AND |
| 8 | 1-based 페이지 `page=1, size=5` | 첫 5개 |
| 9 | `sort=title,asc` | 정렬 확인 |
| 10 | 화이트리스트 외 sort | 기본 정렬 fallback |

### 5.7 L3 — EventController (`@WebMvcTest` + `@MockkBean`)

| # | 시나리오 | 기대 결과 |
|---|---------|---------|
| 1 | POST 정상 | 201 + EventResponse |
| 2 | POST `title=""` | 400 `INVALID_INPUT` |
| 3 | POST `totalQuantity=0` | 400 |
| 4 | POST `startedAt` 누락 | 400 |
| 5 | GET 정상 | 200 + PageResponse |
| 6 | GET `?page=1&size=20` → Pageable slot | `pageNumber=0, pageSize=20` |
| 7 | GET `?statuses=OPEN&statuses=READY` | `List<EventStatus>` 2개 |
| 8 | GET `?keyword=a` (2자 미만) | 400 (`@Size(min=2)`) |
| 9 | GET `/{id}` 정상 | 200 |
| 10 | GET `/{id}` not found | 404 `EVENT_NOT_FOUND` |

### 5.8 L4 — Integration (E2E)

| # | 시나리오 | 기대 결과 |
|---|---------|---------|
| 1 | POST → GET 상세 일관성 | 201 + 200 |
| 2 | POST `startedAt >= endedAt` | 400 `INVALID_DATE_RANGE` |
| 3 | 여러 이벤트 + 필터 검색 | 예상 건수 |
| 4 | 존재하지 않는 UUID | 404 `EVENT_NOT_FOUND` |

---

## 6. Package Structure

```
src/test/kotlin/com/beomjin/springeventlab/
├── coupon/
│   ├── entity/
│   │   ├── DateRangeTest.kt              # L1
│   │   ├── EventStatusTest.kt            # L1
│   │   └── EventTest.kt                  # L1
│   ├── dto/
│   │   ├── EventCreateRequestTest.kt     # L1
│   │   └── EventResponseTest.kt          # L1
│   ├── service/
│   │   ├── EventQueryOrdersTest.kt       # L1 (순수 함수)
│   │   └── EventServiceTest.kt           # L2 ← Mockk
│   ├── repository/
│   │   └── EventQueryRepositoryTest.kt   # L3 @DataJpaTest
│   ├── controller/
│   │   └── EventControllerTest.kt        # L3 @WebMvcTest + @MockkBean
│   └── EventCrudIntegrationTest.kt       # L4
└── support/
    ├── IntegrationTestBase.kt            # (이미 존재)
    └── EventFixture.kt                   # NEW — 테스트 픽스처
```

---

## 7. Implementation Order

> DDD 원칙: 도메인부터. 도메인 테스트가 통과해야 상위 계층이 의미 있음.
> Kotest + MockK 의존성은 L1 시작 전에 완료.

| Step | Task | Files | Priority |
|------|------|-------|:---:|
| 1 | build.gradle.kts — Kotest + MockK 의존성 추가 | `build.gradle.kts` | High |
| 2 | `./gradlew build -x test` 빌드 검증 | — | High |
| 3 | `EventFixture` 작성 | `support/EventFixture.kt` | High |
| 4 | `DateRangeTest` | L1 | High |
| 5 | `EventStatusTest` | L1 | High |
| 6 | `EventTest` | L1 | High |
| 7 | `EventCreateRequestTest` + `EventResponseTest` | L1 | Medium |
| 8 | `EventQueryOrdersTest` | L1 | Medium |
| 9 | **`EventServiceTest`** (MockK 첫 사용) | L2 | High |
| 10 | `EventQueryRepositoryTest` | L3 | High |
| 11 | `EventControllerTest` (`@MockitoBean` + Kotest) | L3 | Medium |
| 12 | `EventCrudIntegrationTest` | L4 | High |
| 13 | `./gradlew test` 전체 통과 확인 | — | High |

---

## 8. Success Criteria

- [ ] build.gradle.kts에 Mockk + springmockk가 추가됐다
- [ ] 모든 FR-T01 ~ FR-T17 시나리오가 테스트로 작성됐다
- [ ] `./gradlew test` 전체 통과
- [ ] L1 Domain Unit 실행 시간 < 1초
- [ ] L2 Service Unit (Mockk) 실행 시간 < 2초
- [ ] 전체 테스트 실행 < 2분
- [ ] 테스트 이름이 한국어로 도메인 규칙 설명 (Living Documentation)
- [ ] `EventFixture` 활용으로 중복 제거
- [ ] Slice 테스트가 `@DataJpaTest` / `@WebMvcTest`로 계층 격리
- [ ] Mockk slot/verify로 Service 호출 검증이 명확
- [ ] springmockk 호환성 문제 발생 시 fallback 적용 (§4.5)

---

## 9. Risk & Mitigation

| Risk | Impact | Mitigation |
|------|--------|-----------|
| ~~springmockk 호환성~~ | — | **도입 보류 결정** (§4.5). L3는 `@MockitoBean` 사용 |
| Kotest 학습 곡선 | 팀원이 DSL 익숙해지는 데 시간 | JUnit 5 + `shouldBe` 혼용부터 시작. Kotest specs(FunSpec)는 선택적으로 L1만 적용 |
| MockK 학습 곡선 | 팀원이 DSL 익숙해지는 데 시간 | `EventServiceTest`를 레퍼런스로 활용 |
| L3 DSL 혼재 (L2 Kotest+MockK vs L3 Kotest+Mockito) | 일관성 저하 | Kotest assertions는 공통, mock DSL만 다름. 테스트 수가 작아 영향 적음 |
| Testcontainers 시작 시간 | 느려짐 | Singleton 패턴(`IntegrationTestBase` 재사용) |
| `@DataJpaTest`에 QueryDSL bean 없음 | `JPAQueryFactory` 주입 실패 | `@Import(JpaConfig::class)` |
| 시간 기반 flaky | 가끔 실패 | `periodMatches(period, now)` 고정 시각 주입 |
| Flyway 마이그레이션 상태 | 이미 적용된 상태로 시작 불가 | Testcontainers가 매번 새 DB → Flyway가 처음부터 실행 |
| MockK가 Value Object/Enum을 mock하려 함 | 테스트 의미 상실 | **원칙**: Domain 객체는 real. Repository/외부 의존만 mock |

---

## 10. Next Steps

1. [ ] Plan 승인 후 `build.gradle.kts`에 Mockk + springmockk 추가 → `./gradlew build` 검증
2. [ ] `/pdca design event-crud-test`로 Design 문서 작성 (각 테스트 클래스의 구체적 `@Test fun` 명세)
3. [ ] 구현 (`Step 1 ~ 11` 순서)
4. [ ] `/pdca analyze event-crud-test`로 Gap 분석
5. [ ] Gap 90% 이상 달성 후 `/pdca report event-crud-test`

---

## Version History

| Version | Date | Changes | Author |
|---------|------|---------|--------|
| 0.1 | 2026-04-10 | Initial draft — Mockk 없이 Spring 기본 스타터만 사용 | beomjin |
| 0.2 | 2026-04-10 | Mockk + springmockk 도입 결정 (임시) — Kotlin final class 대응 + Service 계층 L2 단위 테스트 추가 | beomjin |
| **0.3** | **2026-04-10** | **Kotest(`5.9.1`) + MockK(`1.14.3`) 확정**. Maven Central 버전/호환성 조사 결과 springmockk는 **Spring Boot 3.0 기반**으로 확인되어 도입 보류. L3 Controller Slice는 Spring Boot 4 네이티브 `@MockitoBean` 사용. 모든 계층 공통으로 Kotest assertions(`shouldBe`, `shouldThrow<T>`) 사용. kotlinx-coroutines-test/spring-boot-starter-test/kotlin.plugin.junit 등 불필요 의존성 제외. | beomjin |
