# Event CRUD Test Suite Planning Document

> **Summary**: 현재 구현된 `event-crud` 코드베이스에 대한 DDD 관점 테스트 스위트 작성 계획 (Kotest 6.1.0 Specs + MockK)
>
> **Project**: spring-event-lab
> **Version**: 0.0.1-SNAPSHOT
> **Author**: beomjin
> **Date**: 2026-04-10
> **Status**: Draft (v0.5)
> **Parent Feature**: [01-event-crud.plan.md](01-event-crud.plan.md) (v0.4)

---

## Executive Summary

| Perspective | Content |
|-------------|---------|
| **Problem** | `event-crud`가 DDD 리팩토링을 거쳐 도메인 로직(DateRange 불변식, EventStatus 전이 규칙, Event.issue 원인별 예외)과 QueryDSL 기반 다중 필터 검색이 구현됐지만, **검증하는 자동화 테스트가 없어 회귀(regression) 감지가 불가능**하다. |
| **Solution** | **Kotest 6.1.0**(Specs + Assertions + Power Assert) + **MockK 1.14.9**를 전면 도입한다. Kotest의 `FunSpec`/`DescribeSpec`을 테스트 러너로 사용하고, `ProjectConfig`에서 `SpringExtension` 등록 및 병렬 실행을 구성한다. L3 Controller Slice는 Spring 7 네이티브 `@MockitoBean`을 유지하되, 향후 Custom `@MockkBean`(BeanOverrideProcessor)으로 전환 가능한 경로를 확보한다. |
| **Function/UX Effect** | 개발자가 리팩토링 시 `./gradlew test`로 **회귀를 2분 내 감지**할 수 있다. Kotest Specs의 계층적 DSL(`describe`/`context`/`it`)이 **Living Documentation** 역할을 하며, Power Assert의 시각적 실패 메시지로 디버깅 시간을 단축한다. |
| **Core Value** | DDD 도메인 지식이 **Kotest 6의 Kotlin-native DSL**(`shouldBe`, `shouldThrow<T>`, `withData`)로 Living Documentation화되고, **MockK**가 Kotlin final class를 그대로 mock해 `allopen` 플러그인 없이 깔끔한 단위 테스트가 가능하다. `ProjectConfig`/`PackageConfig` 기반의 전역 설정 체계로 테스트 인프라의 일관성과 확장성을 확보한다. |

---

## 1. Overview

### 1.1 Purpose

`event-crud` feature의 모든 계층을 **Test Pyramid 원칙**에 따라 검증한다. 단순 커버리지 숫자보다 **도메인 불변식 · 유스케이스 경로 · 레이어 경계**에 집중한다.

### 1.2 Why Kotest 6.1.0 + MockK 1.14.9 (결정 사유)

v0.3까지는 Kotest 5.9.1(assertions only) + JUnit 5(runner)를 사용했으나, **Kotest 6.1.0이 안정판으로 출시**되면서 Specs 전면 도입으로 전환한다.

#### 1.2.1 MockK 도입 사유

| 이슈 | Mockito만 사용할 때 | MockK 사용할 때 |
|------|--------------------|----------------|
| **Kotlin final class** | `allopen` 플러그인 또는 `open class` 강제 | **그대로 mock 가능** |
| **Service 단위 테스트** | `@SpringBootTest` 또는 수동 stub | `mockk<EventRepository>()` 한 줄 |
| **테스트 DSL** | `when(repo.findById(any())).thenReturn(...)` | `every { repo.findByIdOrNull(id) } returns ...` |
| **Verify DSL** | `verify(repo, times(1)).save(any())` | `verify(exactly = 1) { repo.save(any()) }` |
| **Slot / Capture** | `ArgumentCaptor` boilerplate | `val slot = slot<T>(); every { f(capture(slot)) } ...` |

#### 1.2.2 Kotest 6.1.0 전면 도입 사유

v0.3에서는 "Kotest assertions만, runner는 JUnit 5"라는 보수적 전략이었으나, Kotest 6.1.0의 안정화와 아래 이점들로 **Specs 전면 도입**으로 변경:

**Kotest 6.1.0 주요 변경점 (vs 5.x)**:

| 변경 | 영향 |
|------|------|
| `@AutoScan` 제거 → **`io.kotest.provided.ProjectConfig`** 필수 위치 | 성능 향상 (불필요한 클래스패스 스캐닝 제거) |
| **PackageConfig** 도입 | 패키지별 타임아웃/격리 모드 세분화 |
| **InstancePerRoot** 격리 모드 | 루트 테스트마다 새 Spec 인스턴스 (상태 격리) |
| **Power Assert** 통합 (Kotlin 2.2+) | 실패 시 모든 하위 식의 실제 값 시각화 |
| KSP 기반 테스트 발견 시스템 | QueryDSL KSP와 빌드 도구 조화 |
| 강화된 병렬 실행 모델 (`LimitedConcurrency`) | CPU 코어 수 기반 최적화 |

**Kotest Specs 도입 범위**:

| Layer | Spec 스타일 | 이유 |
|-------|-----------|------|
| L1 Domain | **DescribeSpec** | `describe("DateRange")` / `context("불변식 위반")` / `it("INVALID_DATE_RANGE를 던진다")` 계층 구조가 도메인 규칙을 자연스럽게 문서화 |
| L2 Service | **FunSpec** | 단순 orchestration 검증에 적합. `test("create는 Entity를 저장하고 Response를 반환한다")` |
| L3 Slice | **FunSpec** | Spring 통합 테스트에서 간결한 구조 |
| L4 Integration | **FunSpec** | E2E 시나리오를 플랫하게 나열 |

**Kotest Assertions vs JUnit/AssertJ 비교**:

| 표현 | JUnit 5 / AssertJ | Kotest Assertions |
|------|-------------------|------------------|
| 값 비교 | `assertEquals(expected, actual)` | `actual shouldBe expected` |
| 예외 검증 | `assertThrows<T> { ... }` | `shouldThrow<T> { ... }` |
| 컬렉션 | `assertThat(list).hasSize(3)` | `list shouldHaveSize 3` |
| null 체크 | `assertNull(value)` | `value.shouldBeNull()` |
| 문자열 포함 | `assertThat(s).contains("abc")` | `s shouldContain "abc"` |

#### 1.2.3 L3 Slice 모킹 전략: springmockk 5.0.1 `@MockkBean`

**배경**: Spring Framework 7.0은 기존 `@MockBean`/`@SpyBean`을 **제거**하고 **BeanOverride API** 기반의 `@MockitoBean`/`@MockitoSpyBean`을 도입했다.

v0.4에서는 `springmockk 4.0.2`가 Spring Boot 3.0 기반이라 도입 불가로 판단했으나, **`springmockk 5.0.1`이 Spring Framework 7 호환으로 출시**되면서 `@MockkBean`을 도입할 수 있게 됐다.

**현재 결정 (v0.5)**: L3 Controller Slice에 **`springmockk 5.0.1`의 `@MockkBean`** 적용. **전 계층 MockK DSL 통일**.

| 접근법 | 장점 | 단점 | 적용 |
|--------|------|------|:---:|
| **`@MockkBean` (springmockk 5.0.1)** | **전 계층 MockK 통일**, 코루틴/final class 완벽 지원 | 외부 라이브러리 1개 추가 | **v0.5 채택** |
| ~~`@MockitoBean` (Spring 7 네이티브)~~ | 프레임워크 기본 지원 | Mockito DSL이 L2의 MockK DSL과 혼재 | ~~v0.4 채택~~ |
| ~~springmockk 4.0.2~~ | ~~간편~~ | Spring Boot 3.0 기반, SB4 호환 X | **도입 불가** |

**전환으로 얻는 이점**:
- L2(MockK) ↔ L3(Mockito) DSL 혼재 제거 → 전 계층 `every`/`verify`/`slot` 통일
- Mockito의 `ArgumentMatchers.any()`가 Kotlin non-null 타입과 충돌하는 문제 해소
- `argumentCaptor<T>()` → `slot<T>()`로 캡처 DSL 통일

**결정**: **Kotest 6.1.0(Specs + Assertions) + MockK 1.14.9 + springmockk 5.0.1**. 전 계층 MockK DSL로 통일.

### 1.3 Testing Philosophy (DDD + Kotest 6 + MockK)

- **Domain tests first**: Value Object, Entity, Enum의 불변식과 상태 전이는 **가장 먼저**, **가장 많이**, **가장 빠르게** 검증 — mock 없이 순수 객체
- **Kotest Specs as runner**: 모든 테스트가 Kotest Spec(`FunSpec`/`DescribeSpec`)을 상속. JUnit 5 `@Test`는 사용하지 않음
- **ProjectConfig 중앙 관리**: `io.kotest.provided.ProjectConfig`에서 SpringExtension, 병렬 실행, 격리 모드를 전역 설정
- **Service as pure unit**: `EventService`는 MockK로 Repository를 대체해 **Spring context 없이** 밀리초 단위 테스트
- **Living Documentation**: Kotest DSL의 계층 구조(`describe`/`context`/`it`)로 도메인 규칙을 자연어화
- **Power Assert**: 실패 시 모든 하위 식의 값을 시각적으로 표시 → 디버깅 시간 단축
- **Data-Driven Testing**: `withData`로 경계값/예외 조건을 누락 없이 검증
- **Don't mock what you don't own**: Repository/Entity는 절대 mock하지 않고 Testcontainers real DB 사용
- **L3는 springmockk**: `@MockkBean`으로 전 계층 MockK 통일
- **Test Pyramid 4-layer**: Domain Unit > Service Unit(MockK) > Slice(Repo + Controller) > Integration(E2E)

### 1.4 Scope Boundary

```
┌─ Layer 1: Domain Unit ─────────────────────────────────────┐
│  DateRange, EventStatus, Event (pure domain, NO mocks)     │  ← 최다, 최고속
│  EventQuery.orders (순수 함수)                              │     DescribeSpec
│  EventCreateRequest.toEntity(), EventResponse.from()        │     + withData
└─────────────────────────────────────────────────────────────┘
┌─ Layer 2: Service Unit (MockK) ────────────────────────────┐
│  EventService (repository를 MockK로 대체, no Spring)       │  ← FunSpec
│  - create/getEvent/getEvents 로직 격리 검증                 │
└─────────────────────────────────────────────────────────────┘
┌─ Layer 3: Slice Tests ─────────────────────────────────────┐
│  EventQueryRepository (@DataJpaTest + Testcontainers)      │  ← FunSpec
│  EventController (@WebMvcTest + @MockkBean EventService)   │     + SpringExtension
└─────────────────────────────────────────────────────────────┘
┌─ Layer 4: Integration ─────────────────────────────────────┐
│  End-to-end CRUD flow (@SpringBootTest + Testcontainers)   │  ← FunSpec
│  @ServiceConnection 자동 구성                               │     최소, 핵심만
└─────────────────────────────────────────────────────────────┘
```

---

## 2. Scope

### 2.1 In Scope

#### Layer 1 — Domain Unit (의존성 0, Mock 없음, DescribeSpec)
- [ ] `DateRangeTest` — 불변식 검증, `isUpcoming/isOngoing/isEnded/contains` 경계값 (withData)
- [ ] `EventStatusTest` — `isIssuable` 프로퍼티, `canTransitionTo`, `transitionTo` Happy/예외 (withData)
- [ ] `EventTest` — 생성 시 불변식(DateRange 경유), `remainingQuantity`, `isIssuable`, `issue`/`open`/`close`
- [ ] `EventCreateRequestTest` — `toEntity()` 체인 검증 (null-safety `!!` 포함)
- [ ] `EventResponseTest` — `from(entity)` 매핑 정확성
- [ ] `EventQueryOrdersTest` — `orders(sort)` 화이트리스트/빈 정렬 fallback (withData)

#### Layer 2 — Service Unit (MockK 기반, FunSpec, NO Spring)
- [ ] `EventServiceTest`
  - `create(request)` — repository.save 호출, EventResponse 반환 검증
  - `getEvent(id)` Happy — findByIdOrNull 반환값 매핑
  - `getEvent(id)` Not Found — `EVENT_NOT_FOUND` 던짐
  - `getEvents(cond, pageable)` — eventQueryRepository.search 호출, 결과 매핑 + `PageResponse.from` 변환
  - **slot**으로 repository에 전달된 인자 검증

#### Layer 3 — Slice Tests (FunSpec + SpringExtension)
- [ ] `EventQueryRepositoryTest` (`@DataJpaTest` + `@Import(JpaConfig::class)` + Testcontainers Postgres)
  - 필터 단독/조합, 다중 정렬, 1-based 페이징, lazy count 동작
- [ ] `EventControllerTest` (`@WebMvcTest(EventController::class)` + `@MockkBean EventService`)
  - Bean Validation 실패 → `INVALID_INPUT` (withData)
  - `@ParameterObject` 바인딩 (`?statuses=OPEN&statuses=READY`)
  - `@PageableDefault` 기본값 + 1-based → 0-based 변환 검증 (slot)
  - 성공 응답 JSON 구조 검증

#### Layer 4 — Integration Tests (FunSpec + SpringExtension, 최소)
- [ ] `EventCrudIntegrationTest` (`FunSpec` 직접 상속 + `@SpringBootTest`)
  - POST 생성 → GET 상세 → GET 검색(필터 조합) → 404 시나리오
  - `DateRange` 불변식이 HTTP 400(`INVALID_DATE_RANGE`)으로 돌아오는지 확인
  - 실제 Flyway 마이그레이션 통합 검증

### 2.2 Out of Scope

- **성능/부하 테스트**: redis-stock feature에서 진행
- **보안 테스트**: 인증/인가 미적용 단계
- **Kafka 이벤트 테스트**: kafka-consumer feature
- **100% 라인 커버리지 목표**: 의미 있는 경로만 집중, 커버리지는 결과지표
- **E2E 회귀 전체**: QA 자동화는 별도 feature
- ~~**Custom @MockkBean 구현**~~: springmockk 5.0.1로 해결됨
- **kotest-property**: Property-based testing은 이 CRUD 규모에 과함

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
| FR-T08 | **EventService.create** — repository.save 호출 + 반환값 매핑 (MockK) | High | L2 Service |
| FR-T09 | **EventService.getEvent** — Not found 시 `EVENT_NOT_FOUND` (MockK) | High | L2 Service |
| FR-T10 | **EventService.getEvents** — eventQueryRepository.search 위임 + PageResponse 매핑 (MockK) | High | L2 Service |
| FR-T11 | EventQueryRepository — 필터 단독/조합/빈 필터 | High | L3 Slice |
| FR-T12 | EventQueryRepository — 1-based 페이지, 화이트리스트 정렬 | High | L3 Slice |
| FR-T13 | EventController — Bean Validation 400 `INVALID_INPUT` | High | L3 Slice |
| FR-T14 | EventController — `@ParameterObject` 바인딩 + 1→0 페이지 변환 | High | L3 Slice |
| FR-T15 | Integration E2E — POST/GET 상세/GET 검색 Happy Path | High | L4 E2E |
| FR-T16 | Integration — `INVALID_DATE_RANGE` HTTP 400 변환 | High | L4 E2E |
| FR-T17 | Integration — 존재하지 않는 UUID → 404 `EVENT_NOT_FOUND` | High | L4 E2E |

### 3.2 Non-Functional Requirements

| Category | Criteria | Target |
|----------|----------|--------|
| Speed (Layer 1) | Domain unit 전체 수행 | **< 1초** (Spring context X) |
| Speed (Layer 2) | Service unit 전체 수행 (MockK) | **< 2초** (Spring context X) |
| Speed (Layer 3) | Slice test 전체 수행 | < 30초 (컨테이너 캐싱 포함) |
| Speed (Layer 4) | Integration test 전체 | < 60초 |
| Speed (전체) | `./gradlew test` 전체 | **< 2분** |
| Isolation | 각 테스트 독립 실행 (SingleInstance + @Transactional rollback) | 의존성 순서 X |
| Readability | Kotest Spec의 계층 구조가 도메인 규칙을 자연어로 설명 | `describe("DateRange") / it("시작이 종료 이후면 INVALID_DATE_RANGE")` |
| Maintainability | 테스트 픽스처 중앙화 (`EventFixture`) | 중복 제거 |
| Stability | 시간 기반 테스트는 고정 시각 주입 | `periodMatches(period, now)` 사용 |
| Coverage | Kover로 커버리지 측정 | L1/L2 대상 라인 커버리지 80%+ (결과지표) |

---

## 4. Testing Strategy

### 4.1 Test Pyramid 배분 (v0.4 — Kotest 6 Specs 전면 도입)

| Layer | Spec Style | Tool | 비중 | 실행 시간 |
|-------|-----------|------|:---:|:---:|
| **L1 Domain Unit** | **DescribeSpec** | Kotest 6 + Power Assert | ~45% | < 1초 |
| **L2 Service Unit** | **FunSpec** | Kotest 6 + **MockK** | ~20% | < 2초 |
| **L3 Slice** | **FunSpec** | Kotest 6 + SpringExtension + `@MockitoBean` | ~25% | ~30초 |
| **L4 Integration** | **FunSpec** | Kotest 6 + SpringExtension + Testcontainers | ~10% | ~60초 |

### 4.2 Test Dependencies (build.gradle.kts 변경 사항)

#### 4.2.1 Kotest 6.1.0 업그레이드

```kotlin
dependencies {
    // ... 기존 의존성 유지

    // Kotest 6.1.0 — Kotlin-native test framework (Specs + Assertions)
    testImplementation("io.kotest:kotest-runner-junit5:6.1.0")
    testImplementation("io.kotest:kotest-assertions-core:6.1.0")
    testImplementation("io.kotest:kotest-extensions-spring:6.1.0")  // NEW — SpringExtension
    testImplementation("io.kotest:kotest-framework-datatest:6.1.0") // NEW — withData

    // MockK 1.14.9 — Kotlin-native mocking
    testImplementation("io.mockk:mockk:1.14.9")

    // SpringMockK 5.0.1 — @MockkBean for Spring (L3 Slice)
    testImplementation("com.ninja-squad:springmockk:5.0.1")
}
```

#### 4.2.2 Power Assert (Kotlin 컴파일러 플러그인)

```kotlin
// build.gradle.kts plugins 블록은 수정 불필요
// Kotlin 2.3.20은 Power Assert를 내장 지원 (kotlin.powerAssert)

kotlin {
    compilerOptions {
        freeCompilerArgs.addAll(
            "-Xpower-assert"  // Power Assert 활성화
        )
    }
}
```

> **참고**: Kotlin 2.2+에서 Power Assert는 `kotlin-power-assert-compiler-plugin`이 아닌 내장 컴파일러 옵션으로 제공된다. `assert()` 및 Kotest의 `withClue` 등에서 자동으로 상세 실패 메시지를 생성한다.

#### 4.2.3 버전 선택 근거 (v0.5)

| 라이브러리 | v0.3 | v0.4 | v0.5 | 변경 사유 |
|-----------|------|------|------|---------|
| **Kotest** | 5.9.1 | **6.1.0** | 6.1.0 | 안정판 출시. ProjectConfig 필수화, PackageConfig, Power Assert 통합, KSP 기반 테스트 발견 |
| **MockK** | 1.14.3 | **1.14.9** | 1.14.9 | 최신 안정판, Kotlin 2.3 완전 호환, 코루틴 지원 강화 |
| **springmockk** | — | ~~4.0.2 (도입 불가)~~ | **5.0.1 (신규)** | Spring Framework 7 호환판 출시. `@MockkBean`으로 전 계층 MockK 통일 |
| kotest-extensions-spring | — | **6.1.0** (신규) | 6.1.0 | Kotest Specs에서 `@SpringBootTest` / `@DataJpaTest` / `@WebMvcTest` 사용을 위한 SpringExtension |
| kotest-framework-datatest | — | **6.1.0** (신규) | 6.1.0 | `withData` 데이터 드리븐 테스팅 |
| Kover | 0.9.8 | 0.9.8 (기존) | 0.9.8 | 이미 build.gradle.kts에 포함 |

**기존 유지 (변경 없음)**:
- `spring-boot-starter-webmvc-test`, `spring-boot-starter-data-jpa-test` 등 모듈화된 테스트 스타터 (이미 존재)
- `spring-boot-testcontainers` (이미 존재)
- `testcontainers-postgresql`, `testcontainers-kafka` (이미 존재)
- `kotlin-test-junit5` (Kotest runner가 JUnit Platform 위에서 동작하므로 유지)

**제외한 의존성 (과공학 회피)**:
- ~~`kotest-property`~~ — Property-based testing은 이 CRUD 규모에 과함
- ~~`kotlinx-coroutines-test`~~ — 현재 coroutines 미사용
- ~~`springmockk`~~ — ~~Spring Boot 3.0 기반, SB4 미지원~~ → **v0.5에서 5.0.1 도입** (Spring Framework 7 호환)

### 4.3 Kotest 6 전역 구성

#### 4.3.1 ProjectConfig (필수)

Kotest 6에서는 `AbstractProjectConfig`를 상속받는 클래스가 **반드시 `io.kotest.provided.ProjectConfig`** 경로에 위치해야 자동 감지된다. `@AutoScan`은 제거되었다.

```kotlin
// src/test/kotlin/io/kotest/provided/ProjectConfig.kt
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

**설정 결정 근거**:
- **SpringExtension**: 모든 Spring 테스트 어노테이션(`@SpringBootTest`, `@DataJpaTest`, `@WebMvcTest`)과 Kotest Specs의 호환을 보장. 생성자 주입 지원
- **SingleInstance**: Spring의 애플리케이션 컨텍스트 캐싱 전략과 최적 부합. Spec 인스턴스를 공유하므로 컨텍스트 재로딩 최소화
- **specExecutionMode = Sequential**: `SingleInstance`에서 병렬 실행(`Concurrent`)하면 여러 테스트가 동일 인스턴스의 상태에 동시 접근하여 race condition 발생. L1/L2가 밀리초 단위이므로 순차 실행해도 전체 2분 목표에 영향 없음

#### 4.3.2 PackageConfig (선택적)

Kotest 6.0에서 도입된 `PackageConfig`는 패키지별 설정을 적용한다. 설정 우선순위: `개별 .config()` > `Spec defaultTestConfig` > `PackageConfig` > `ProjectConfig`.

```kotlin
// src/test/kotlin/com/beomjin/springeventlab/coupon/entity/PackageConfig.kt
package com.beomjin.springeventlab.coupon.entity

import io.kotest.core.config.AbstractPackageConfig
import kotlin.time.Duration.Companion.seconds

class PackageConfig : AbstractPackageConfig() {
    // L1 Domain 테스트는 1초 내 완료 보장
    override val timeout = 1.seconds
}
```

```kotlin
// src/test/kotlin/com/beomjin/springeventlab/coupon/repository/PackageConfig.kt
package com.beomjin.springeventlab.coupon.repository

import io.kotest.core.config.AbstractPackageConfig
import kotlin.time.Duration.Companion.seconds

class PackageConfig : AbstractPackageConfig() {
    // L3 Slice 테스트는 Testcontainers 시작 포함해 30초 허용
    override val timeout = 30.seconds
}
```

**적용 전략**: L1(1초), L2(2초), L3(30초), L4(60초)로 패키지별 타임아웃 차등 설정.

#### 4.3.3 격리 모드 (Isolation Mode)

| 모드 | 동작 | 적합 케이스 |
|------|------|-----------|
| **SingleInstance** (채택) | Spec 인스턴스 1개 공유 | Spring 컨텍스트 캐싱과 호환. L3/L4에 최적 |
| InstancePerTest | 매 테스트마다 새 인스턴스 | 상태 격리 필요 시. 컨텍스트 재로딩 비용 |
| InstancePerRoot | 루트 테스트마다 새 인스턴스 | SingleInstance와 InstancePerTest의 절충안 |
| InstancePerLeaf | 리프 테스트마다 새 인스턴스 | 최대 격리. 성능 비용 최대 |

**결정**: `SingleInstance`를 전역 기본값으로. L1/L2는 mock을 `beforeTest`에서 재초기화하여 상태 격리 달성.

### 4.4 Layer별 책임

#### 4.4.1 L1 — Domain Unit (DescribeSpec, 순수 Kotlin)

**`DateRangeTest`** (DescribeSpec + withData):

```kotlin
class DateRangeTest : DescribeSpec({

    describe("DateRange 생성") {
        context("유효한 범위") {
            it("startedAt < endedAt이면 정상 생성된다") {
                val range = DateRange(Instant.parse("2026-07-01T00:00:00Z"), Instant.parse("2026-07-07T23:59:59Z"))
                range.startedAt shouldBeBefore range.endedAt
            }
        }

        context("불변식 위반") {
            withData(
                nameFn = { "startedAt=${it.first}, endedAt=${it.second}" },
                Pair(Instant.parse("2026-07-07T00:00:00Z"), Instant.parse("2026-07-01T00:00:00Z")),  // 역순
                Pair(Instant.parse("2026-07-01T00:00:00Z"), Instant.parse("2026-07-01T00:00:00Z")),  // 동일
            ) { (start, end) ->
                shouldThrow<BusinessException> { DateRange(start, end) }
                    .errorCode shouldBe ErrorCode.INVALID_DATE_RANGE
            }
        }
    }

    describe("contains") {
        context("반개구간 [start, end)") {
            val range = EventFixture.dateRange()

            it("start는 포함한다") { range.contains(range.startedAt) shouldBe true }
            it("end는 제외한다") { range.contains(range.endedAt) shouldBe false }
            it("start 직전은 제외한다") { range.contains(range.startedAt.minusMillis(1)) shouldBe false }
        }
    }
})
```

**`EventStatusTest`** (DescribeSpec + withData):

```kotlin
class EventStatusTest : DescribeSpec({

    describe("isIssuable") {
        withData(
            EventStatus.READY to false,
            EventStatus.OPEN to true,
            EventStatus.CLOSED to false,
        ) { (status, expected) ->
            status.isIssuable shouldBe expected
        }
    }

    describe("canTransitionTo") {
        withData(
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
})
```

**도구**: Kotest 6.1.0 DescribeSpec + withData + Power Assert
**원칙**: Value Object와 Entity는 real object로 테스트. Mock은 금지.

#### 4.4.2 L2 — Service Unit (FunSpec + MockK)

```kotlin
class EventServiceTest : FunSpec({

    val eventRepository = mockk<EventRepository>()
    val eventQueryRepository = mockk<EventQueryRepository>()
    val service = EventService(eventRepository, eventQueryRepository)

    beforeTest { clearAllMocks() }

    test("create는 request를 Entity로 변환해 저장하고 EventResponse를 반환한다") {
        val request = EventFixture.createRequest()
        val saved = EventFixture.event()
        every { eventRepository.save(any()) } returns saved

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

    test("getEvents는 eventQueryRepository에 검색조건과 Pageable을 그대로 위임한다") {
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
})
```

**MockK 핵심 DSL**:
- `mockk<T>()` — interface/class mock (Kotlin final 지원)
- `every { ... } returns ...` — stubbing
- `verify(exactly = N) { ... }` — invocation 검증
- `slot<T>()` + `capture(slot)` — ArgumentCaptor 대체
- `clearAllMocks()` — `beforeTest`에서 호출하여 SingleInstance 격리 모드 보완

**원칙**: Service는 **thin orchestration layer**이므로 테스트도 가볍다. Repository는 mock, Entity/DTO는 real object.

#### 4.4.3 L3 — Slice Tests (FunSpec + SpringExtension)

**`EventQueryRepositoryTest`** (`@DataJpaTest` + Testcontainers):
- JPA/QueryDSL만 로드 (Controller/Service 제외)
- `@Import(JpaConfig::class)`로 `JPAQueryFactory` 주입
- 필터 단독/조합/복합, 정렬 화이트리스트, lazy count 검증
- 시간 기반 테스트는 `periodMatches(period, fixedNow)`로 고정

**`EventControllerTest`** (`@WebMvcTest` + `@MockkBean` + Kotest FunSpec):

> springmockk 5.0.1의 `@MockkBean`으로 **전 계층 MockK DSL 통일**. assertions도 Kotest.

```kotlin
@WebMvcTest(EventController::class)
class EventControllerTest(
    private val mockMvc: MockMvc,
    @MockkBean private val eventService: EventService,
) : FunSpec({

    test("POST events 정상 요청은 201과 EventResponse를 반환한다") {
        every { eventService.create(any()) } returns EventFixture.response()

        mockMvc.post("/api/v1/events") {
            contentType = MediaType.APPLICATION_JSON
            content = EventFixture.createRequestJson()
        }.andExpect {
            status { isCreated() }
            jsonPath("$.id") { exists() }
        }
    }

    test("GET events는 1-based page 파라미터를 0-based Pageable로 Service에 전달한다") {
        val pageableSlot = slot<Pageable>()
        every { eventService.getEvents(any(), capture(pageableSlot)) } returns PageResponse.from(Page.empty())

        mockMvc.get("/api/v1/events?page=1&size=20")
            .andExpect { status { isOk() } }

        pageableSlot.captured.pageNumber shouldBe 0
        pageableSlot.captured.pageSize shouldBe 20
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
})
```

**참고**: Kotest `SpringExtension`이 `ProjectConfig`에 등록되어 있으므로, 생성자 주입으로 `MockMvc`와 `@MockkBean`을 직접 받을 수 있다. `@Autowired lateinit var` 대신 **생성자 매개변수 방식**이 Kotest에서 권장된다.

#### 4.4.4 L4 — Integration (FunSpec + @ServiceConnection)

**`EventCrudIntegrationTest`** (`FunSpec` 직접 상속 + `@SpringBootTest`):
- 실제 Testcontainers Postgres + Flyway
- `@ServiceConnection` 기반 자동 구성 (§4.8 참조)
- 최소 시나리오만:
  1. POST 생성 → 201, 반환 id로 GET 상세 → 200
  2. 여러 이벤트 생성 → 검색 필터 조합 동작
  3. 존재하지 않는 UUID → 404 `EVENT_NOT_FOUND`
  4. POST `startedAt > endedAt` → 400 `INVALID_DATE_RANGE`
- `@Transactional` + rollback 격리

### 4.5 Power Assert & Data-Driven Testing

#### Power Assert

Kotlin 2.2+ 내장 기능으로, assert 실패 시 식의 모든 중간 값을 시각적으로 표시한다:

```
assertion failed:
event.remainingQuantity shouldBe 99
|     |                 |
|     100               99
Event(title=여름 쿠폰, totalQuantity=100, issuedQuantity=0)
```

**활성화**: `build.gradle.kts`에 `-Xpower-assert` 컴파일러 옵션 추가 (§4.2.2).

#### Data-Driven Testing (withData)

`kotest-framework-datatest`의 `withData`로 경계값/예외 조건을 누락 없이 검증:

```kotlin
context("EventStatus 전이 규칙") {
    withData(
        TransitionCase(READY, OPEN, true),
        TransitionCase(READY, CLOSED, false),
        TransitionCase(OPEN, CLOSED, true),
        // ...
    ) { (from, to, allowed) ->
        from.canTransitionTo(to) shouldBe allowed
    }
}
```

**적용 대상**:
- L1 `DateRangeTest` — 불변식 위반 케이스, contains 경계값
- L1 `EventStatusTest` — isIssuable, canTransitionTo 전이표
- L1 `EventQueryOrdersTest` — 화이트리스트/비허용 정렬 필드

### 4.6 Spec 실행 모드 전략

Kotest 6에서는 `parallelism` 프로퍼티가 제거되고 `specExecutionMode`/`testExecutionMode`로 대체되었다.

| 옵션 | 동작 |
|------|------|
| `SpecExecutionMode.Sequential` (채택) | Spec 파일을 순서대로 실행 |
| `SpecExecutionMode.Concurrent` | 모든 Spec을 동시 실행 |
| `SpecExecutionMode.LimitedConcurrency(N)` | 최대 N개 Spec 동시 실행 |

**Sequential 선택 이유**: `SingleInstance` 격리 모드는 Spec 인스턴스를 1개만 생성한다. 여기에 `Concurrent`를 조합하면 **여러 테스트가 동일 인스턴스의 공유 상태(mock, 변수)에 동시 접근**하여 race condition이 발생한다. L1/L2는 밀리초 단위이고 L3/L4도 30~60초이므로 순차 실행해도 전체 2분 목표에 충분하다.

| 설정 | 값 | 효과 |
|------|----|----|
| `specExecutionMode` | `Sequential` | race condition 방지 |
| `isolationMode` | `SingleInstance` | Spring 컨텍스트 캐싱 유지 |
| L1/L2 `beforeTest` | `clearAllMocks()` | mock 상태 격리 |
| L3/L4 | `@Transactional` rollback | DB 상태 격리 |

### 4.7 IntegrationTestBase 리팩토링

현재 `IntegrationTestBase`는 `@DynamicPropertySource`로 수동 매핑하고 있다. Spring Boot 4의 `@ServiceConnection`으로 전환하면 설정을 단순화할 수 있다:

**현재** (수동 매핑):
```kotlin
@DynamicPropertySource
fun configureProperties(registry: DynamicPropertyRegistry) {
    registry.add("spring.datasource.url") { postgres.jdbcUrl }
    registry.add("spring.datasource.username") { postgres.username }
    // ...
}
```

**현재 코드** (이미 @ServiceConnection 적용 완료):
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

**IntegrationTestBase는 Spec을 상속하지 않는다**. Kotest에서 abstract base class에 Spec 상속을 조합하면 `open` 클래스 이슈와 Spring 어노테이션 호환 문제가 발생할 수 있다. IntegrationTestBase는 순수한 **컨테이너 홀더 + Spring 설정** 역할만 하고, 하위 테스트 클래스가 직접 FunSpec을 상속한다:

```kotlin
@SpringBootTest
@ActiveProfiles("test")
class EventCrudIntegrationTest : FunSpec({
    // IntegrationTestBase.companion의 @ServiceConnection 컨테이너를
    // Spring Context가 자동 감지하므로 상속 불필요
    test("POST 생성 → GET 상세") { ... }
})
```

> **참고**: `@ServiceConnection` 컨테이너는 companion object에 `@JvmStatic`으로 선언되어 있으면 Spring Context가 클래스패스에서 자동 감지한다. IntegrationTestBase를 상속하지 않아도 동일한 컨테이너를 공유한다.

### 4.8 Testing Tools (최종 v0.5)

| 도구 | 버전 | 용도 | 상태 |
|------|------|------|:---:|
| **Kotest runner-junit5** | **6.1.0** | **Kotest Specs 런너 (FunSpec/DescribeSpec)** | ✅ 적용 |
| **Kotest assertions-core** | **6.1.0** | **Kotlin-native assertions** | ✅ 적용 |
| **Kotest extensions-spring** | **6.1.0** | **SpringExtension (DI 지원)** | ✅ 적용 |
| **Kotest framework-datatest** | **6.1.0** | **withData 데이터 드리븐 테스팅** | ✅ 적용 |
| **MockK** | **1.14.9** | **Kotlin-native mock (L1~L4 전 계층)** | ✅ 적용 |
| **springmockk** | **5.0.1** | **`@MockkBean` — L3 Spring Slice mock** | ⏳ **신규** |
| ~~`@MockitoBean`~~ | ~~Spring 7 내장~~ | ~~L3 Controller slice~~ | ~~v0.4~~ → springmockk로 대체 |
| ~~`mockito-kotlin`~~ | ~~starter에 포함~~ | ~~Kotlin Mockito DSL~~ | ~~v0.4~~ → MockK DSL로 대체 |
| MockMvc | starter에 포함 | HTTP 레이어 | ✅ 기존 |
| Testcontainers | 2.0 | 실 DB/인프라 + `@ServiceConnection` | ✅ 기존 |
| Kover | 0.9.8 | Kotlin 전용 커버리지 측정 | ✅ 기존 |
| `kotlin-test-junit5` | Kotlin BOM | JUnit Platform 호환 | ✅ 기존 |
| Power Assert | Kotlin 2.3 내장 | 실패 시 식의 중간값 시각화 | ✅ 적용 |

### 4.9 Risk: Spring Boot 4 호환성

| 라이브러리 | 버전 | Spring Boot 4 호환 | 확인 방법 |
|-----------|------|:---:|---------|
| Kotest | 6.1.0 | ✅ (프레임워크 독립) | JUnit Platform 위에서 동작. Spring 버전 무관 |
| Kotest extensions-spring | 6.1.0 | ✅ | SpringExtension이 Spring TCF와 통합 |
| MockK | 1.14.9 | ✅ | Kotlin 2.3.20 호환. Spring 무관 |
| **springmockk** | **5.0.1** | ✅ | Spring Framework 7 호환 명시. `./gradlew build -x test` 통과 확인 |
| Power Assert | Kotlin 2.3 내장 | ✅ | 컴파일러 수준. 런타임 의존성 없음 |

### 4.10 Test Fixture 전략

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

### 5.1 L1 — DateRange (DescribeSpec + withData)

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

> 시나리오 2~3, 4~7은 `withData`로 데이터 드리븐 테스트 구성.

### 5.2 L1 — EventStatus (DescribeSpec + withData)

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

> 시나리오 1~3은 `withData(READY to false, OPEN to true, CLOSED to false)`, 4~8도 `withData(Triple(...))`.

### 5.3 L1 — Event (DescribeSpec)

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

### 5.4 L1 — EventQuery.orders (DescribeSpec + withData)

| # | 시나리오 | 기대 결과 |
|---|---------|---------|
| 1 | `Sort.unsorted()` | `[createdAt.desc()]` fallback |
| 2 | `Sort.by(ASC, "title")` | `[title.asc()]` |
| 3 | `Sort.by("startedAt")` | `period.startedAt` 매핑 |
| 4 | 다중 정렬 | 2개 OrderSpecifier |
| 5 | 화이트리스트 외 `password` | 무시 + fallback |
| 6 | 일부 유효 + 일부 무효 | 유효한 것만 |

### 5.5 L2 — EventService (FunSpec + MockK)

| # | 시나리오 | Mock 설정 | 기대 결과 |
|---|---------|----------|---------|
| 1 | `create(request)` | `every { repo.save(any()) } returns event` | EventResponse 반환, save 1회 호출 |
| 2 | `create`에 전달된 Entity 검증 | `val slot = slot<Event>(); every { repo.save(capture(slot)) } ...` | slot.captured.title 등 필드 확인 |
| 3 | `getEvent(id)` Happy | `every { repo.findByIdOrNull(id) } returns event` | EventResponse 반환 |
| 4 | `getEvent(id)` Not Found | `every { repo.findByIdOrNull(id) } returns null` | `EVENT_NOT_FOUND` throw |
| 5 | `getEvents(cond, pageable)` | `every { queryRepo.search(cond, pageable) } returns PageImpl(...)` | PageResponse 매핑 |
| 6 | `getEvents`에 전달된 cond/pageable | `slot<EventSearchCond>()` + `slot<Pageable>()` | 위임 파라미터 검증 |

### 5.6 L3 — EventQueryRepository (FunSpec + @DataJpaTest + Testcontainers)

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

### 5.7 L3 — EventController (FunSpec + @WebMvcTest + @MockkBean)

| # | 시나리오 | 기대 결과 |
|---|---------|---------|
| 1 | POST 정상 | 201 + EventResponse |
| 2 | POST `title=""` | 400 `INVALID_INPUT` |
| 3 | POST `totalQuantity=0` | 400 |
| 4 | POST `startedAt` 누락 | 400 |
| 5 | GET 정상 | 200 + PageResponse |
| 6 | GET `?page=1&size=20` → Pageable | `pageNumber=0, pageSize=20` |
| 7 | GET `?statuses=OPEN&statuses=READY` | `List<EventStatus>` 2개 |
| 8 | GET `?keyword=a` (2자 미만) | 400 (`@Size(min=2)`) |
| 9 | GET `/{id}` 정상 | 200 |
| 10 | GET `/{id}` not found | 404 `EVENT_NOT_FOUND` |

### 5.8 L4 — Integration (FunSpec + E2E)

| # | 시나리오 | 기대 결과 |
|---|---------|---------|
| 1 | POST → GET 상세 일관성 | 201 + 200 |
| 2 | POST `startedAt >= endedAt` | 400 `INVALID_DATE_RANGE` |
| 3 | 여러 이벤트 + 필터 검색 | 예상 건수 |
| 4 | 존재하지 않는 UUID | 404 `EVENT_NOT_FOUND` |

---

## 6. Package Structure

```
src/test/kotlin/
├── io/kotest/provided/
│   └── ProjectConfig.kt                  # Kotest 6 전역 설정 (SpringExtension, Sequential)
└── com/beomjin/springeventlab/
    ├── coupon/
    │   ├── entity/
    │   │   ├── PackageConfig.kt            # PackageConfig (timeout=1s)
    │   │   ├── DateRangeTest.kt           # L1 DescribeSpec + withData
    │   │   ├── EventStatusTest.kt         # L1 DescribeSpec + withData
    │   │   └── EventTest.kt              # L1 DescribeSpec
    │   ├── dto/
    │   │   ├── EventCreateRequestTest.kt  # L1 DescribeSpec
    │   │   └── EventResponseTest.kt       # L1 DescribeSpec
    │   ├── service/
    │   │   ├── EventQueryOrdersTest.kt    # L1 DescribeSpec + withData
    │   │   └── EventServiceTest.kt        # L2 FunSpec + MockK
    │   ├── repository/
    │   │   ├── PackageConfig.kt            # PackageConfig (timeout=30s)
    │   │   └── EventQueryRepositoryTest.kt # L3 FunSpec + @DataJpaTest
    │   ├── controller/
    │   │   └── EventControllerTest.kt     # L3 FunSpec + @WebMvcTest + @MockkBean
    │   └── EventCrudIntegrationTest.kt    # L4 FunSpec + @ServiceConnection
    └── support/
        ├── IntegrationTestBase.kt         # (리팩토링: @ServiceConnection)
        └── EventFixture.kt               # NEW — 테스트 픽스처
```

---

## 7. Implementation Order

> DDD 원칙: 도메인부터. 도메인 테스트가 통과해야 상위 계층이 의미 있음.
> Kotest 6.1.0 의존성 및 ProjectConfig는 L1 시작 전에 완료.

| Step | Task | Files | Priority |
|------|------|-------|:---:|
| 0 | **Kotest 6.1.0 + MockK 1.14.9 의존성 업그레이드** + kotest-extensions-spring, kotest-framework-datatest 추가 + Power Assert 컴파일러 옵션 | `build.gradle.kts` | High |
| 1 | `./gradlew build -x test` 빌드 검증 | — | High |
| 2 | **`ProjectConfig`** 작성 (io.kotest.provided 패키지) | `ProjectConfig.kt` | High |
| 3 | **`IntegrationTestBase`** 리팩토링 (@ServiceConnection 전환) | `IntegrationTestBase.kt` | High |
| 4 | `EventFixture` 작성 | `support/EventFixture.kt` | High |
| 5 | `DateRangeTest` (DescribeSpec + withData) | L1 | High |
| 6 | `EventStatusTest` (DescribeSpec + withData) | L1 | High |
| 7 | `EventTest` (DescribeSpec) | L1 | High |
| 8 | `EventCreateRequestTest` + `EventResponseTest` | L1 | Medium |
| 9 | `EventQueryOrdersTest` (DescribeSpec + withData) | L1 | Medium |
| 10 | **`EventServiceTest`** (FunSpec + MockK) | L2 | High |
| 11 | `EventQueryRepositoryTest` (FunSpec + @DataJpaTest) | L3 | High |
| 12 | `EventControllerTest` (FunSpec + @MockkBean + withData) | L3 | Medium |
| 13 | `EventCrudIntegrationTest` (FunSpec + IntegrationTestBase) | L4 | High |
| 14 | PackageConfig 추가 (L1/L3 타임아웃 차등) | `PackageConfig.kt` × 2 | Low |
| 15 | `./gradlew test` 전체 통과 확인 + Kover 리포트 | — | High |

---

## 8. Success Criteria

- [ ] build.gradle.kts에 Kotest 6.1.0 + MockK 1.14.9 + springmockk 5.0.1 + extensions-spring + framework-datatest가 설정됐다
- [ ] `io.kotest.provided.ProjectConfig`가 존재하고 SpringExtension이 등록됐다
- [ ] 모든 FR-T01 ~ FR-T17 시나리오가 Kotest Spec으로 작성됐다
- [ ] `./gradlew test` 전체 통과
- [ ] L1 Domain Unit 실행 시간 < 1초
- [ ] L2 Service Unit (MockK) 실행 시간 < 2초
- [ ] 전체 테스트 실행 < 2분
- [ ] L1은 DescribeSpec, L2~L4는 FunSpec으로 일관된 Spec 스타일 사용
- [ ] L1 경계값 테스트에 withData가 적용됐다
- [ ] `EventFixture` 활용으로 중복 제거
- [ ] Slice 테스트가 `@DataJpaTest` / `@WebMvcTest`로 계층 격리
- [ ] MockK slot/verify로 Service 호출 검증이 명확
- [ ] IntegrationTestBase가 @ServiceConnection 기반으로 리팩토링됐다
- [ ] Power Assert 컴파일러 옵션이 활성화됐다
- [ ] Kover 리포트가 생성된다

---

## 9. Risk & Mitigation

| Risk | Impact | Mitigation |
|------|--------|-----------|
| **Kotest 6 마이그레이션** | ProjectConfig 위치 변경, API 차이 | Kotest 6 마이그레이션 가이드 참조. `@AutoScan` 제거 → `io.kotest.provided` 필수 |
| kotest-extensions-spring 호환성 | Spring Boot 4 + Kotest 6 통합 이슈 가능 | 6.1.0 안정판 사용. 문제 시 SpringExtension 없이 `@SpringBootTest` + JUnit Platform fallback |
| ~~L3 DSL 혼재~~ | ~~일관성 저하~~ | **해결됨**: springmockk 5.0.1로 전 계층 MockK 통일 |
| Power Assert 컴파일러 플래그 | 빌드 설정 변경 필요 | `-Xpower-assert`는 실험적 기능이 아닌 안정 기능 (Kotlin 2.2+) |
| Testcontainers @ServiceConnection | 기존 @DynamicPropertySource와 동작 차이 | 점진적 전환. Redis GenericContainer가 @ServiceConnection 미지원 시 DynamicPropertySource 유지 |
| 병렬 실행 시 DB 상태 충돌 | flaky test | `@Transactional` rollback + SingleInstance 격리. 필요 시 `@Isolate` |
| `@DataJpaTest`에 QueryDSL bean 없음 | `JPAQueryFactory` 주입 실패 | `@Import(JpaConfig::class)` |
| 시간 기반 flaky | 가끔 실패 | `periodMatches(period, now)` 고정 시각 주입 |
| Flyway 마이그레이션 상태 | 이미 적용된 상태로 시작 불가 | Testcontainers가 매번 새 DB → Flyway가 처음부터 실행 |
| MockK가 Value Object/Enum을 mock하려 함 | 테스트 의미 상실 | **원칙**: Domain 객체는 real. Repository/외부 의존만 mock |

---

## 10. Next Steps

1. [ ] Plan 승인 후 `build.gradle.kts`에 Kotest 6.1.0 + MockK 1.14.9 의존성 업그레이드 → `./gradlew build` 검증
2. [ ] `/pdca design 06-event-crud-test`로 Design 문서 갱신 (Kotest Spec 기반 `describe`/`test` 명세)
3. [ ] 구현 (`Step 0 ~ 15` 순서)
4. [ ] `/pdca analyze 06-event-crud-test`로 Gap 분석
5. [ ] Gap 90% 이상 달성 후 `/pdca report 06-event-crud-test`

---

## Version History

| Version | Date | Changes | Author |
|---------|------|---------|--------|
| 0.1 | 2026-04-10 | Initial draft — MockK 없이 Spring 기본 스타터만 사용 | beomjin |
| 0.2 | 2026-04-10 | MockK + springmockk 도입 결정 (임시) — Kotlin final class 대응 + Service 계층 L2 단위 테스트 추가 | beomjin |
| 0.3 | 2026-04-10 | Kotest(`5.9.1`) + MockK(`1.14.3`) 확정. springmockk Spring Boot 3.0 기반으로 도입 보류. L3는 `@MockitoBean` 사용. Kotest assertions 공통 적용. | beomjin |
| 0.4 | 2026-04-13 | Kotest 6.1.0 전면 도입: Specs 전 계층 적용. ProjectConfig + PackageConfig 구성. MockK 1.14.9. Power Assert. IntegrationTestBase @ServiceConnection 리팩토링. L3는 @MockitoBean 유지. | beomjin |
| **0.5** | **2026-04-14** | **springmockk 5.0.1 도입**: Spring Framework 7 호환판 출시로 `@MockkBean` 적용 가능해짐. L3 `@MockitoBean` → `@MockkBean` 전환, **전 계층 MockK DSL 통일**. L3 Controller Validation에 `withData` 적용. Custom BeanOverrideProcessor 확장 경로 제거 (불필요). | beomjin |
