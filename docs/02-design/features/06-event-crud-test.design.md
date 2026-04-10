# Event CRUD Test Suite Design Document

> **Summary**: `event-crud` 기능에 대한 Kotest + MockK 기반 테스트 스위트의 상세 설계. 각 테스트 클래스의 구조·의존성·케이스 명세.
>
> **Project**: spring-event-lab
> **Version**: 0.0.1-SNAPSHOT
> **Author**: beomjin
> **Date**: 2026-04-10
> **Status**: Draft
> **Planning Doc**: [06-event-crud-test.plan.md](../../01-plan/features/06-event-crud-test.plan.md) (v0.3)

---

## Executive Summary

| Perspective | Content |
|-------------|---------|
| **Problem** | Plan v0.3에서 확정된 Kotest + MockK 기반 4계층 테스트 전략의 구체적 구현 명세가 필요하다. 각 테스트 클래스가 어떤 어노테이션/의존성/mock 전략을 쓰는지, 어떤 `@Test fun` 들을 포함하는지 정확히 정해야 병렬 구현이 가능하다. |
| **Solution** | 10개 테스트 파일(L1 Domain 6개 + L2 Service 1개 + L3 Slice 2개 + L4 Integration 1개) + `EventFixture` 1개의 구조/의존성/테스트 케이스를 파일 단위로 상세 명세. Kotest assertions + MockK DSL의 표준 패턴을 코드 스니펫으로 제시한다. |
| **Function/UX Effect** | 구현자가 Design 문서만 보고 순서대로 테스트를 작성할 수 있다. 각 `@Test fun` 이름과 검증 대상이 명확해 빠진 케이스가 없고, 동일한 테스트 스타일(네이밍/어설션/픽스처)로 일관성을 유지한다. |
| **Core Value** | 테스트 코드가 **Living Documentation**이 되도록 한국어 백틱 함수명 + Kotest DSL 조합의 표준을 확립. 향후 redis-stock, kafka-consumer 등 후속 feature의 테스트 작성 시 이 Design 문서가 템플릿 역할을 한다. |

---

## 1. Overview

### 1.1 Design Goals

- **Plan v0.3 충실 구현**: 4계층(L1~L4) 테스트 Pyramid의 구체적 파일/클래스/테스트 케이스 정의
- **Kotest + MockK 표준화**: 모든 계층에서 동일한 어설션 DSL(`shouldBe`, `shouldThrow<T>`) 사용
- **Living Documentation**: 한국어 백틱 함수명으로 도메인 규칙을 테스트 이름에 그대로 반영
- **Zero Fresh Setup**: 각 테스트는 독립 실행 가능하도록 mock 재생성/트랜잭션 rollback 적용
- **재사용 가능한 Fixture**: `EventFixture` object로 모든 도메인 객체 생성을 중앙화

### 1.2 Design Principles

- **Don't mock what you don't own**: Repository/Entity는 real object, Testcontainers real DB 사용
- **Don't over-mock**: Value Object/Enum은 mock 금지. 항상 real instance
- **Thin Service = Thin Service Test**: Service 테스트는 위임 검증 위주 (MockK slot 활용)
- **Fast feedback**: L1/L2는 Spring context 없이 밀리초 단위 수행
- **Explicit over implicit**: `@TestConfiguration`, `@Import` 등 명시적 Wiring

---

## 2. Component Structure

### 2.1 파일 구조

```
src/test/kotlin/com/beomjin/springeventlab/
├── support/
│   ├── IntegrationTestBase.kt           # (기존)
│   └── EventFixture.kt                  # NEW — 모든 도메인 객체 생성 중앙화
├── coupon/
│   ├── entity/
│   │   ├── DateRangeTest.kt             # L1 — Value Object 불변식
│   │   ├── EventStatusTest.kt           # L1 — isIssuable, 전이 규칙
│   │   └── EventTest.kt                 # L1 — 도메인 로직
│   ├── dto/
│   │   ├── request/
│   │   │   └── EventCreateRequestTest.kt  # L1 — toEntity() 체인
│   │   └── response/
│   │       └── EventResponseTest.kt     # L1 — from(entity) 매핑
│   ├── service/
│   │   ├── EventQueryTest.kt            # L1 — orders(sort) 순수 함수
│   │   └── EventServiceTest.kt          # L2 — MockK 기반
│   ├── repository/
│   │   └── EventQueryRepositoryTest.kt  # L3 — @DataJpaTest + TC
│   ├── controller/
│   │   └── EventControllerTest.kt       # L3 — @WebMvcTest + @MockitoBean
│   └── EventCrudIntegrationTest.kt      # L4 — @SpringBootTest + TC
```

### 2.2 테스트 클래스 Matrix

| # | 클래스 | Layer | 의존성 | Spring Context | Mock |
|---|--------|:---:|--------|:---:|:---:|
| 1 | `DateRangeTest` | L1 | Kotest assertions | ❌ | ❌ |
| 2 | `EventStatusTest` | L1 | Kotest assertions | ❌ | ❌ |
| 3 | `EventTest` | L1 | Kotest + `EventFixture` | ❌ | ❌ |
| 4 | `EventCreateRequestTest` | L1 | Kotest + `EventFixture` | ❌ | ❌ |
| 5 | `EventResponseTest` | L1 | Kotest + `EventFixture` | ❌ | ❌ |
| 6 | `EventQueryTest` | L1 | Kotest | ❌ | ❌ |
| 7 | `EventServiceTest` | L2 | Kotest + **MockK** + `EventFixture` | ❌ | ✅ MockK |
| 8 | `EventQueryRepositoryTest` | L3 | `@DataJpaTest` + TC + `@Import(JpaConfig::class)` + Kotest | ✅ 부분 (JPA) | ❌ |
| 9 | `EventControllerTest` | L3 | `@WebMvcTest` + `@MockitoBean` + Kotest | ✅ 부분 (Web) | ✅ Mockito |
| 10 | `EventCrudIntegrationTest` | L4 | `IntegrationTestBase` + Kotest | ✅ 전체 | ❌ |

---

## 3. Shared Fixture — `EventFixture`

### 3.1 위치
`src/test/kotlin/com/beomjin/springeventlab/support/EventFixture.kt`

### 3.2 책임
- 모든 테스트에서 `Event`, `DateRange`, `EventCreateRequest`, `EventResponse`, JSON 문자열을 생성하는 **단일 진입점**
- 기본값을 제공하고, 테스트별 차이 필드만 인자로 받아 오버라이드
- UUID/Instant 같은 값을 **고정**해 테스트 재현성 확보

### 3.3 코드

```kotlin
package com.beomjin.springeventlab.support

import com.beomjin.springeventlab.coupon.dto.request.EventCreateRequest
import com.beomjin.springeventlab.coupon.dto.response.EventResponse
import com.beomjin.springeventlab.coupon.entity.Event
import com.beomjin.springeventlab.event.entity.EventStatus
import com.beomjin.springeventlab.global.common.DateRange
import java.time.Instant
import java.util.UUID

object EventFixture {

    val DEFAULT_START: Instant = Instant.parse("2026-07-01T00:00:00Z")
    val DEFAULT_END: Instant = Instant.parse("2026-07-07T23:59:59Z")
    val DEFAULT_NOW: Instant = Instant.parse("2026-07-03T12:00:00Z")  // 기간 중간값

    fun dateRange(
        start: Instant = DEFAULT_START,
        end: Instant = DEFAULT_END,
    ): DateRange = DateRange(start, end)

    fun event(
        title: String = "여름 쿠폰 이벤트",
        totalQuantity: Int = 100,
        status: EventStatus = EventStatus.READY,
        period: DateRange = dateRange(),
    ): Event = Event(
        title = title,
        totalQuantity = totalQuantity,
        eventStatus = status,
        period = period,
    )

    fun createRequest(
        title: String? = "여름 쿠폰 이벤트",
        totalQuantity: Int? = 100,
        startedAt: Instant? = DEFAULT_START,
        endedAt: Instant? = DEFAULT_END,
    ): EventCreateRequest = EventCreateRequest(
        title = title,
        totalQuantity = totalQuantity,
        startedAt = startedAt,
        endedAt = endedAt,
    )

    fun response(event: Event = event()): EventResponse = EventResponse.from(event)

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

## 4. L1 — Domain Unit Tests

### 4.1 `DateRangeTest`

**책임**: Value Object의 불변식과 도메인 메서드 검증
**의존성**: Kotest assertions, `EventFixture` (optional)
**Spring**: ❌

#### 4.1.1 Test Cases

```kotlin
package com.beomjin.springeventlab.global.common

import com.beomjin.springeventlab.global.exception.BusinessException
import com.beomjin.springeventlab.global.exception.ErrorCode
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test
import java.time.Instant

class DateRangeTest {

    private val start = Instant.parse("2026-07-01T00:00:00Z")
    private val end = Instant.parse("2026-07-07T23:59:59Z")

    @Test
    fun `startedAt이 endedAt보다 이전이면 정상 생성된다`() {
        val range = DateRange(start, end)
        range.startedAt shouldBe start
        range.endedAt shouldBe end
    }

    @Test
    fun `startedAt과 endedAt이 같으면 INVALID_DATE_RANGE를 던진다`() {
        val ex = shouldThrow<BusinessException> { DateRange(start, start) }
        ex.errorCode shouldBe ErrorCode.INVALID_DATE_RANGE
    }

    @Test
    fun `startedAt이 endedAt보다 이후면 INVALID_DATE_RANGE를 던진다`() {
        val ex = shouldThrow<BusinessException> { DateRange(end, start) }
        ex.errorCode shouldBe ErrorCode.INVALID_DATE_RANGE
    }

    @Test
    fun `contains는 startedAt 시각을 포함한다`() {
        val range = DateRange(start, end)
        range.contains(start) shouldBe true
    }

    @Test
    fun `contains는 endedAt 시각을 제외한다 (반개구간)`() {
        val range = DateRange(start, end)
        range.contains(end) shouldBe false
    }

    @Test
    fun `contains는 범위 밖 시각을 제외한다`() {
        val range = DateRange(start, end)
        range.contains(start.minusMillis(1)) shouldBe false
        range.contains(end.plusMillis(1)) shouldBe false
    }

    @Test
    fun `contains는 범위 중간 시각을 포함한다`() {
        val range = DateRange(start, end)
        val mid = Instant.parse("2026-07-04T12:00:00Z")
        range.contains(mid) shouldBe true
    }

    @Test
    fun `isUpcoming은 현재가 startedAt 이전일 때 true`() {
        val range = DateRange(start, end)
        range.isUpcoming(start.minusSeconds(1)) shouldBe true
        range.isUpcoming(start) shouldBe false
    }

    @Test
    fun `isOngoing은 현재가 startedAt 이상 endedAt 미만일 때 true`() {
        val range = DateRange(start, end)
        range.isOngoing(start) shouldBe true
        range.isOngoing(end.minusSeconds(1)) shouldBe true
        range.isOngoing(end) shouldBe false
    }

    @Test
    fun `isEnded는 현재가 endedAt 이상일 때 true`() {
        val range = DateRange(start, end)
        range.isEnded(end) shouldBe true
        range.isEnded(end.plusSeconds(1)) shouldBe true
        range.isEnded(end.minusSeconds(1)) shouldBe false
    }
}
```

**총 10개 테스트**.

### 4.2 `EventStatusTest`

**책임**: `isIssuable` 속성 + 상태 전이 규칙 검증

```kotlin
package com.beomjin.springeventlab.coupon.entity

import com.beomjin.springeventlab.event.entity.EventStatus
import com.beomjin.springeventlab.global.exception.BusinessException
import com.beomjin.springeventlab.global.exception.ErrorCode
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test

class EventStatusTest {

    // --- isIssuable ---

    @Test
    fun `READY는 발급 불가능 상태다`() {
        EventStatus.READY.isIssuable shouldBe false
    }

    @Test
    fun `OPEN은 발급 가능 상태다`() {
        EventStatus.OPEN.isIssuable shouldBe true
    }

    @Test
    fun `CLOSED는 발급 불가능 상태다`() {
        EventStatus.CLOSED.isIssuable shouldBe false
    }

    // --- canTransitionTo ---

    @Test
    fun `READY는 OPEN으로 전이 가능하다`() {
        EventStatus.READY.canTransitionTo(EventStatus.OPEN) shouldBe true
    }

    @Test
    fun `READY는 CLOSED로 직접 전이 불가능하다 (스킵 금지)`() {
        EventStatus.READY.canTransitionTo(EventStatus.CLOSED) shouldBe false
    }

    @Test
    fun `OPEN은 CLOSED로 전이 가능하다`() {
        EventStatus.OPEN.canTransitionTo(EventStatus.CLOSED) shouldBe true
    }

    @Test
    fun `OPEN은 READY로 역전이 불가능하다`() {
        EventStatus.OPEN.canTransitionTo(EventStatus.READY) shouldBe false
    }

    @Test
    fun `CLOSED는 어떤 상태로도 전이 불가능하다 (종료 상태)`() {
        EventStatus.CLOSED.canTransitionTo(EventStatus.READY) shouldBe false
        EventStatus.CLOSED.canTransitionTo(EventStatus.OPEN) shouldBe false
    }

    // --- transitionTo ---

    @Test
    fun `transitionTo 성공 시 다음 상태를 반환한다`() {
        EventStatus.READY.transitionTo(EventStatus.OPEN) shouldBe EventStatus.OPEN
        EventStatus.OPEN.transitionTo(EventStatus.CLOSED) shouldBe EventStatus.CLOSED
    }

    @Test
    fun `transitionTo 실패 시 EVENT_INVALID_STATUS_TRANSITION을 던진다`() {
        val ex = shouldThrow<BusinessException> {
            EventStatus.READY.transitionTo(EventStatus.CLOSED)
        }
        ex.errorCode shouldBe ErrorCode.EVENT_INVALID_STATUS_TRANSITION
    }
}
```

**총 10개 테스트**.

### 4.3 `EventTest`

**책임**: Event 생성, `remainingQuantity`, `isIssuable`, `issue`/`open`/`close` 도메인 로직 검증

```kotlin
package com.beomjin.springeventlab.coupon.entity

import com.beomjin.springeventlab.event.entity.EventStatus
import com.beomjin.springeventlab.global.exception.BusinessException
import com.beomjin.springeventlab.global.exception.ErrorCode
import com.beomjin.springeventlab.support.EventFixture
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test

class EventTest {

    // --- 생성 ---

    @Test
    fun `Event는 생성 시 issuedQuantity가 0이고 remainingQuantity가 totalQuantity와 같다`() {
        val event = EventFixture.event(totalQuantity = 100)
        event.issuedQuantity shouldBe 0
        event.remainingQuantity shouldBe 100
    }

    // --- isIssuable ---

    @Test
    fun `isIssuable은 상태가 READY면 false다`() {
        val event = EventFixture.event(status = EventStatus.READY)
        event.isIssuable() shouldBe false
    }

    @Test
    fun `isIssuable은 상태가 OPEN이고 재고가 있을 때 true다`() {
        val event = EventFixture.event(status = EventStatus.OPEN, totalQuantity = 10)
        event.isIssuable() shouldBe true
    }

    @Test
    fun `isIssuable은 상태가 OPEN이어도 재고가 0이면 false다`() {
        val event = EventFixture.event(status = EventStatus.OPEN, totalQuantity = 1)
        event.issue()  // 1장 발급 → 재고 0
        event.isIssuable() shouldBe false
    }

    @Test
    fun `isIssuable은 상태가 CLOSED면 false다`() {
        val event = EventFixture.event(status = EventStatus.OPEN)
        event.close()
        event.isIssuable() shouldBe false
    }

    // --- issue ---

    @Test
    fun `issue는 상태가 READY면 EVENT_NOT_OPEN을 던진다`() {
        val event = EventFixture.event(status = EventStatus.READY)
        val ex = shouldThrow<BusinessException> { event.issue() }
        ex.errorCode shouldBe ErrorCode.EVENT_NOT_OPEN
    }

    @Test
    fun `issue는 재고가 소진되면 EVENT_OUT_OF_STOCK을 던진다`() {
        val event = EventFixture.event(status = EventStatus.OPEN, totalQuantity = 1)
        event.issue()  // 1장 발급
        val ex = shouldThrow<BusinessException> { event.issue() }
        ex.errorCode shouldBe ErrorCode.EVENT_OUT_OF_STOCK
    }

    @Test
    fun `issue 성공 시 issuedQuantity가 1 증가한다`() {
        val event = EventFixture.event(status = EventStatus.OPEN, totalQuantity = 10)
        event.issue()
        event.issuedQuantity shouldBe 1
        event.remainingQuantity shouldBe 9
    }

    // --- open / close ---

    @Test
    fun `open은 READY 상태에서 OPEN으로 전환한다`() {
        val event = EventFixture.event(status = EventStatus.READY)
        event.open()
        event.eventStatus shouldBe EventStatus.OPEN
    }

    @Test
    fun `open은 이미 OPEN 상태면 EVENT_INVALID_STATUS_TRANSITION을 던진다`() {
        val event = EventFixture.event(status = EventStatus.OPEN)
        val ex = shouldThrow<BusinessException> { event.open() }
        ex.errorCode shouldBe ErrorCode.EVENT_INVALID_STATUS_TRANSITION
    }

    @Test
    fun `close는 OPEN 상태에서 CLOSED로 전환한다`() {
        val event = EventFixture.event(status = EventStatus.OPEN)
        event.close()
        event.eventStatus shouldBe EventStatus.CLOSED
    }

    @Test
    fun `close는 READY 상태면 EVENT_INVALID_STATUS_TRANSITION을 던진다`() {
        val event = EventFixture.event(status = EventStatus.READY)
        val ex = shouldThrow<BusinessException> { event.close() }
        ex.errorCode shouldBe ErrorCode.EVENT_INVALID_STATUS_TRANSITION
    }
}
```

**총 12개 테스트**.

### 4.4 `EventCreateRequestTest`

**책임**: `toEntity()` 변환이 DateRange Value Object를 경유하는지, null-safety가 작동하는지

```kotlin
package com.beomjin.springeventlab.coupon.dto.request

import com.beomjin.springeventlab.event.entity.EventStatus
import com.beomjin.springeventlab.global.exception.BusinessException
import com.beomjin.springeventlab.global.exception.ErrorCode
import com.beomjin.springeventlab.support.EventFixture
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test

class EventCreateRequestTest {

    @Test
    fun `toEntity는 DTO 필드를 Event로 정확히 매핑한다`() {
        val request = EventFixture.createRequest()
        val event = request.toEntity()

        event.title shouldBe "여름 쿠폰 이벤트"
        event.totalQuantity shouldBe 100
        event.eventStatus shouldBe EventStatus.READY
        event.period.startedAt shouldBe EventFixture.DEFAULT_START
        event.period.endedAt shouldBe EventFixture.DEFAULT_END
    }

    @Test
    fun `toEntity는 startedAt이 endedAt보다 이후면 INVALID_DATE_RANGE를 던진다 (DateRange 경유)`() {
        val request = EventFixture.createRequest(
            startedAt = EventFixture.DEFAULT_END,
            endedAt = EventFixture.DEFAULT_START,
        )
        val ex = shouldThrow<BusinessException> { request.toEntity() }
        ex.errorCode shouldBe ErrorCode.INVALID_DATE_RANGE
    }

    @Test
    fun `toEntity는 title이 null이면 NPE 계열 예외를 던진다 (Bean Validation 통과 후 단계)`() {
        val request = EventFixture.createRequest(title = null)
        shouldThrow<NullPointerException> { request.toEntity() }
    }
}
```

**총 3개 테스트**.

### 4.5 `EventResponseTest`

**책임**: Entity → DTO 매핑이 모든 필드를 빠짐없이 복사하고 `period` 내부 값에 접근하는지

```kotlin
package com.beomjin.springeventlab.coupon.dto.response

import com.beomjin.springeventlab.event.entity.EventStatus
import com.beomjin.springeventlab.support.EventFixture
import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test

class EventResponseTest {

    @Test
    fun `from은 Event의 모든 필드를 EventResponse에 복사한다`() {
        val event = EventFixture.event(
            title = "테스트",
            totalQuantity = 50,
            status = EventStatus.OPEN,
        )
        val response = EventResponse.from(event)

        response.id shouldBe event.id
        response.title shouldBe "테스트"
        response.totalQuantity shouldBe 50
        response.issuedQuantity shouldBe 0
        response.remainingQuantity shouldBe 50
        response.eventStatus shouldBe EventStatus.OPEN
    }

    @Test
    fun `from은 DateRange 내부의 startedAt과 endedAt을 복사한다`() {
        val event = EventFixture.event()
        val response = EventResponse.from(event)

        response.startedAt shouldBe event.period.startedAt
        response.endedAt shouldBe event.period.endedAt
    }

    @Test
    fun `from은 issuedQuantity 반영 후 remainingQuantity를 재계산한다`() {
        val event = EventFixture.event(status = EventStatus.OPEN, totalQuantity = 10)
        event.issue()
        val response = EventResponse.from(event)

        response.issuedQuantity shouldBe 1
        response.remainingQuantity shouldBe 9
    }
}
```

**총 3개 테스트**.

### 4.6 `EventQueryTest` (orders만 순수 함수 테스트)

**책임**: `EventQuery.orders(sort)` 화이트리스트 기반 정렬 변환 검증
**주의**: Where 조건 함수(`keywordMatches`, `periodMatches` 등)는 `BooleanExpression` 동일성 비교가 어려워 L3 Repository 테스트에서 간접 검증. 본 테스트는 **orders만** 다룬다.

```kotlin
package com.beomjin.springeventlab.coupon.service

import com.beomjin.springeventlab.coupon.entity.QEvent.Companion.event
import io.kotest.matchers.collections.shouldHaveSize
import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test
import org.springframework.data.domain.Sort

class EventQueryTest {

    @Test
    fun `orders는 빈 Sort에 대해 createdAt DESC 기본 정렬을 반환한다`() {
        val result = EventQuery.orders(Sort.unsorted())
        result.size shouldBe 1
        result[0].toString() shouldBe event.createdAt.desc().toString()
    }

    @Test
    fun `orders는 title asc 요청을 OrderSpecifier로 변환한다`() {
        val result = EventQuery.orders(Sort.by(Sort.Direction.ASC, "title"))
        result.size shouldBe 1
        result[0].toString() shouldBe event.title.asc().toString()
    }

    @Test
    fun `orders는 startedAt 요청을 period startedAt 경로로 매핑한다`() {
        val result = EventQuery.orders(Sort.by(Sort.Direction.DESC, "startedAt"))
        result.size shouldBe 1
        result[0].toString() shouldBe event.period.startedAt.desc().toString()
    }

    @Test
    fun `orders는 다중 정렬을 모두 변환한다`() {
        val sort = Sort.by(
            Sort.Order.asc("title"),
            Sort.Order.desc("createdAt"),
        )
        val result = EventQuery.orders(sort)
        result.toList() shouldHaveSize 2
    }

    @Test
    fun `orders는 화이트리스트 외 필드를 무시하고 기본 정렬로 fallback한다`() {
        val result = EventQuery.orders(Sort.by("password"))
        result.size shouldBe 1
        result[0].toString() shouldBe event.createdAt.desc().toString()
    }

    @Test
    fun `orders는 유효한 필드와 무효한 필드가 섞이면 유효한 것만 반환한다`() {
        val sort = Sort.by(
            Sort.Order.asc("title"),
            Sort.Order.desc("unknownField"),
        )
        val result = EventQuery.orders(sort)
        result.size shouldBe 1
        result[0].toString() shouldBe event.title.asc().toString()
    }
}
```

**총 6개 테스트**.

---

## 5. L2 — Service Unit Test (MockK)

### 5.1 `EventServiceTest`

**책임**: `EventService`의 위임 로직 검증 (Spring 없이)
**의존성**: Kotest + MockK + `EventFixture`
**Spring**: ❌ (순수 객체 조립)

```kotlin
package com.beomjin.springeventlab.coupon.service

import com.beomjin.springeventlab.coupon.dto.request.EventSearchCond
import com.beomjin.springeventlab.coupon.entity.Event
import com.beomjin.springeventlab.coupon.repository.EventQueryRepository
import com.beomjin.springeventlab.coupon.repository.EventRepository
import com.beomjin.springeventlab.global.exception.BusinessException
import com.beomjin.springeventlab.global.exception.ErrorCode
import com.beomjin.springeventlab.support.EventFixture
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.matchers.collections.shouldHaveSize
import io.kotest.matchers.shouldBe
import io.mockk.every
import io.mockk.mockk
import io.mockk.slot
import io.mockk.verify
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.springframework.data.domain.Page
import org.springframework.data.domain.PageImpl
import org.springframework.data.domain.PageRequest
import org.springframework.data.domain.Pageable
import org.springframework.data.repository.findByIdOrNull
import java.util.UUID

class EventServiceTest {

    private lateinit var eventRepository: EventRepository
    private lateinit var eventQueryRepository: EventQueryRepository
    private lateinit var service: EventService

    @BeforeEach
    fun setUp() {
        eventRepository = mockk()
        eventQueryRepository = mockk()
        service = EventService(eventRepository, eventQueryRepository)
    }

    // --- create ---

    @Test
    fun `create는 request를 Event로 변환해 repository에 저장하고 EventResponse를 반환한다`() {
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

    @Test
    fun `create에 전달된 Event의 title이 request의 title과 일치한다`() {
        val request = EventFixture.createRequest(title = "캡처 테스트 이벤트")
        val slot = slot<Event>()
        every { eventRepository.save(capture(slot)) } answers { slot.captured }

        service.create(request)

        slot.captured.title shouldBe "캡처 테스트 이벤트"
    }

    // --- getEvent ---

    @Test
    fun `getEvent는 존재하는 ID에 대해 EventResponse를 반환한다`() {
        val event = EventFixture.event()
        every { eventRepository.findByIdOrNull(event.id) } returns event

        val response = service.getEvent(event.id)

        response.id shouldBe event.id
    }

    @Test
    fun `getEvent는 존재하지 않는 ID에 대해 EVENT_NOT_FOUND를 던진다`() {
        val id = UUID.randomUUID()
        every { eventRepository.findByIdOrNull(id) } returns null

        val ex = shouldThrow<BusinessException> { service.getEvent(id) }
        ex.errorCode shouldBe ErrorCode.EVENT_NOT_FOUND
    }

    // --- getEvents ---

    @Test
    fun `getEvents는 검색조건과 Pageable을 그대로 EventQueryRepository에 위임한다`() {
        val cond = EventSearchCond(keyword = "여름")
        val pageable = PageRequest.of(0, 20)
        val page: Page<Event> = PageImpl(listOf(EventFixture.event()), pageable, 1)

        val slotCond = slot<EventSearchCond>()
        val slotPageable = slot<Pageable>()
        every {
            eventQueryRepository.search(capture(slotCond), capture(slotPageable))
        } returns page

        val result = service.getEvents(cond, pageable)

        slotCond.captured.keyword shouldBe "여름"
        slotPageable.captured.pageSize shouldBe 20
        result.content shouldHaveSize 1
    }

    @Test
    fun `getEvents는 Page content를 EventResponse로 매핑한다`() {
        val events = listOf(
            EventFixture.event(title = "A"),
            EventFixture.event(title = "B"),
        )
        every {
            eventQueryRepository.search(any(), any())
        } returns PageImpl(events, PageRequest.of(0, 10), 2)

        val result = service.getEvents(EventSearchCond(), PageRequest.of(0, 10))

        result.content.map { it.title } shouldBe listOf("A", "B")
    }
}
```

**총 6개 테스트**.

---

## 6. L3 — Slice Tests

### 6.1 `EventQueryRepositoryTest` (`@DataJpaTest` + Testcontainers)

**책임**: QueryDSL 기반 검색의 필터 조합, 1-based 페이지, 정렬, lazy count 동작 검증
**의존성**: JPA, `@Import(JpaConfig::class)` for `JPAQueryFactory`, Testcontainers Postgres

```kotlin
package com.beomjin.springeventlab.coupon.repository

import com.beomjin.springeventlab.coupon.dto.request.EventPeriod
import com.beomjin.springeventlab.coupon.dto.request.EventSearchCond
import com.beomjin.springeventlab.coupon.entity.Event
import com.beomjin.springeventlab.event.entity.EventStatus
import com.beomjin.springeventlab.global.common.DateRange
import com.beomjin.springeventlab.global.config.JpaConfig
import com.beomjin.springeventlab.support.EventFixture
import io.kotest.matchers.collections.shouldHaveSize
import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.autoconfigure.jdbc.AutoConfigureTestDatabase
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest
import org.springframework.context.annotation.Import
import org.springframework.data.domain.PageRequest
import org.springframework.data.domain.Sort
import org.springframework.test.context.DynamicPropertyRegistry
import org.springframework.test.context.DynamicPropertySource
import org.testcontainers.containers.PostgreSQLContainer
import org.testcontainers.junit.jupiter.Testcontainers
import org.testcontainers.utility.DockerImageName
import java.time.Instant

@DataJpaTest
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@Import(JpaConfig::class)
@Testcontainers
class EventQueryRepositoryTest {

    companion object {
        @JvmStatic
        val postgres = PostgreSQLContainer(DockerImageName.parse("postgres:18-alpine"))

        init { postgres.start() }

        @JvmStatic
        @DynamicPropertySource
        fun properties(registry: DynamicPropertyRegistry) {
            registry.add("spring.datasource.url") { postgres.jdbcUrl }
            registry.add("spring.datasource.username") { postgres.username }
            registry.add("spring.datasource.password") { postgres.password }
        }
    }

    @Autowired lateinit var eventRepository: EventRepository
    @Autowired lateinit var queryRepository: EventQueryRepository

    private lateinit var summer: Event
    private lateinit var winter: Event
    private lateinit var ended: Event

    @BeforeEach
    fun setUp() {
        eventRepository.deleteAll()
        summer = eventRepository.save(EventFixture.event(
            title = "여름 쿠폰 이벤트",
            status = EventStatus.OPEN,
            period = DateRange(
                Instant.parse("2026-07-01T00:00:00Z"),
                Instant.parse("2026-07-31T23:59:59Z"),
            ),
        ))
        winter = eventRepository.save(EventFixture.event(
            title = "겨울 쿠폰 이벤트",
            status = EventStatus.READY,
            period = DateRange(
                Instant.parse("2026-12-01T00:00:00Z"),
                Instant.parse("2026-12-31T23:59:59Z"),
            ),
        ))
        ended = eventRepository.save(EventFixture.event(
            title = "종료된 이벤트",
            status = EventStatus.CLOSED,
            period = DateRange(
                Instant.parse("2026-01-01T00:00:00Z"),
                Instant.parse("2026-01-31T23:59:59Z"),
            ),
        ))
    }

    @Test
    fun `필터 없이 조회하면 모든 이벤트를 반환한다`() {
        val result = queryRepository.search(EventSearchCond(), PageRequest.of(0, 10))
        result.content shouldHaveSize 3
    }

    @Test
    fun `keyword로 title 부분 일치 검색한다`() {
        val cond = EventSearchCond(keyword = "여름")
        val result = queryRepository.search(cond, PageRequest.of(0, 10))
        result.content shouldHaveSize 1
        result.content[0].title shouldBe "여름 쿠폰 이벤트"
    }

    @Test
    fun `statuses로 여러 상태를 IN 필터링한다`() {
        val cond = EventSearchCond(statuses = listOf(EventStatus.OPEN, EventStatus.READY))
        val result = queryRepository.search(cond, PageRequest.of(0, 10))
        result.content shouldHaveSize 2
    }

    @Test
    fun `hasRemainingStock true는 재고가 남은 이벤트만 반환한다`() {
        val cond = EventSearchCond(hasRemainingStock = true)
        val result = queryRepository.search(cond, PageRequest.of(0, 10))
        // 3개 모두 issuedQuantity=0이라 전부 재고 있음
        result.content shouldHaveSize 3
    }

    @Test
    fun `createdFrom과 createdTo 범위로 생성일 필터링한다`() {
        val now = Instant.now()
        val cond = EventSearchCond(
            createdFrom = now.minusSeconds(3600),
            createdTo = now.plusSeconds(3600),
        )
        val result = queryRepository.search(cond, PageRequest.of(0, 10))
        result.content shouldHaveSize 3  // 모두 방금 생성됨
    }

    @Test
    fun `복수 필터는 AND로 결합된다`() {
        val cond = EventSearchCond(
            keyword = "쿠폰",
            statuses = listOf(EventStatus.OPEN),
        )
        val result = queryRepository.search(cond, PageRequest.of(0, 10))
        result.content shouldHaveSize 1
        result.content[0].title shouldBe "여름 쿠폰 이벤트"
    }

    @Test
    fun `페이징은 Pageable에 따라 동작한다`() {
        val result = queryRepository.search(EventSearchCond(), PageRequest.of(0, 2))
        result.content shouldHaveSize 2
        result.totalElements shouldBe 3
        result.totalPages shouldBe 2
    }

    @Test
    fun `title 오름차순 정렬이 적용된다`() {
        val pageable = PageRequest.of(0, 10, Sort.by(Sort.Direction.ASC, "title"))
        val result = queryRepository.search(EventSearchCond(), pageable)
        result.content.map { it.title } shouldBe listOf("겨울 쿠폰 이벤트", "여름 쿠폰 이벤트", "종료된 이벤트")
    }

    @Test
    fun `화이트리스트 외 정렬 필드는 무시되고 기본 정렬(createdAt DESC)이 적용된다`() {
        val pageable = PageRequest.of(0, 10, Sort.by("unknownField"))
        val result = queryRepository.search(EventSearchCond(), pageable)
        result.content shouldHaveSize 3
    }
}
```

**총 9개 테스트**.

**주의사항**:
- `@DataJpaTest`는 기본적으로 H2 in-memory DB로 교체하므로 `@AutoConfigureTestDatabase(replace = NONE)`로 비활성화하고 Testcontainers Postgres 사용
- `@Import(JpaConfig::class)`로 `JPAQueryFactory` bean 주입
- `period` 필터 테스트는 `Instant.now()` 기반이라 flaky 가능성 있음 → 현재는 생략, 필요 시 시간 mocking 도입

### 6.2 `EventControllerTest` (`@WebMvcTest` + `@MockitoBean`)

**책임**: HTTP 레이어 바인딩, Bean Validation, `@ParameterObject`, 1→0 페이지 변환 검증

```kotlin
package com.beomjin.springeventlab.coupon.controller

import com.beomjin.springeventlab.coupon.service.EventService
import com.beomjin.springeventlab.global.common.PageResponse
import com.beomjin.springeventlab.support.EventFixture
import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test
import org.mockito.kotlin.any
import org.mockito.kotlin.argumentCaptor
import org.mockito.kotlin.given
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest
import org.springframework.data.domain.Page
import org.springframework.data.domain.Pageable
import org.springframework.http.MediaType
import org.springframework.test.context.bean.override.mockito.MockitoBean
import org.springframework.test.web.servlet.MockMvc
import org.springframework.test.web.servlet.get
import org.springframework.test.web.servlet.post

@WebMvcTest(EventController::class)
class EventControllerTest {

    @Autowired lateinit var mockMvc: MockMvc
    @MockitoBean lateinit var eventService: EventService

    // --- POST /api/v1/events ---

    @Test
    fun `POST events는 정상 요청에 대해 201과 EventResponse를 반환한다`() {
        given(eventService.create(any())).willReturn(EventFixture.response())

        mockMvc.post("/api/v1/events") {
            contentType = MediaType.APPLICATION_JSON
            content = EventFixture.createRequestJson()
        }.andExpect {
            status { isCreated() }
            jsonPath("$.title") { exists() }
        }
    }

    @Test
    fun `POST events는 title이 빈값이면 400 INVALID_INPUT을 반환한다`() {
        val json = EventFixture.createRequestJson(title = "")
        mockMvc.post("/api/v1/events") {
            contentType = MediaType.APPLICATION_JSON
            content = json
        }.andExpect { status { isBadRequest() } }
    }

    @Test
    fun `POST events는 totalQuantity가 0이면 400을 반환한다`() {
        val json = EventFixture.createRequestJson(totalQuantity = 0)
        mockMvc.post("/api/v1/events") {
            contentType = MediaType.APPLICATION_JSON
            content = json
        }.andExpect { status { isBadRequest() } }
    }

    // --- GET /api/v1/events (검색) ---

    @Test
    fun `GET events는 1-based page 파라미터를 0-based Pageable로 Service에 전달한다`() {
        val captor = argumentCaptor<Pageable>()
        given(eventService.getEvents(any(), captor.capture()))
            .willReturn(PageResponse.from(Page.empty()))

        mockMvc.get("/api/v1/events?page=1&size=20")
            .andExpect { status { isOk() } }

        captor.firstValue.pageNumber shouldBe 0  // 1 → 0 변환 검증 (Kotest)
        captor.firstValue.pageSize shouldBe 20
    }

    @Test
    fun `GET events는 기본 페이징(page=1, size=20)을 적용한다`() {
        val captor = argumentCaptor<Pageable>()
        given(eventService.getEvents(any(), captor.capture()))
            .willReturn(PageResponse.from(Page.empty()))

        mockMvc.get("/api/v1/events")
            .andExpect { status { isOk() } }

        captor.firstValue.pageNumber shouldBe 0
        captor.firstValue.pageSize shouldBe 20
    }

    @Test
    fun `GET events keyword가 2자 미만이면 400을 반환한다`() {
        mockMvc.get("/api/v1/events?keyword=a")
            .andExpect { status { isBadRequest() } }
    }

    // --- GET /api/v1/events/{id} ---

    @Test
    fun `GET events-id는 정상 조회 시 200을 반환한다`() {
        given(eventService.getEvent(any())).willReturn(EventFixture.response())
        mockMvc.get("/api/v1/events/019644a2-3b00-7f8a-a1e2-4c5d6e7f8a9b")
            .andExpect { status { isOk() } }
    }
}
```

**총 7개 테스트**.

**주의사항**:
- `@MockitoBean`은 Spring Boot 3.4+에서 import 경로가 `org.springframework.test.context.bean.override.mockito.MockitoBean`
- `mockito-kotlin`의 `any()`, `given()`, `argumentCaptor<T>()`는 Spring Boot test starter에 포함
- 어설션은 **Kotest `shouldBe`**로 L2와 일관성 유지

---

## 7. L4 — Integration Test

### 7.1 `EventCrudIntegrationTest`

**책임**: POST 생성 → GET 상세/검색 → 에러 케이스의 E2E 검증
**의존성**: 기존 `IntegrationTestBase` 상속 (Testcontainers Postgres/Redis/Kafka)

```kotlin
package com.beomjin.springeventlab.coupon

import com.beomjin.springeventlab.coupon.repository.EventRepository
import com.beomjin.springeventlab.support.EventFixture
import com.beomjin.springeventlab.support.IntegrationTestBase
import com.fasterxml.jackson.databind.ObjectMapper
import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc
import org.springframework.http.MediaType
import org.springframework.test.web.servlet.MockMvc
import org.springframework.test.web.servlet.get
import org.springframework.test.web.servlet.post
import org.springframework.transaction.annotation.Transactional

@AutoConfigureMockMvc
@Transactional
class EventCrudIntegrationTest : IntegrationTestBase() {

    @Autowired lateinit var mockMvc: MockMvc
    @Autowired lateinit var eventRepository: EventRepository
    @Autowired lateinit var objectMapper: ObjectMapper

    @BeforeEach
    fun cleanUp() {
        eventRepository.deleteAll()
    }

    @Test
    fun `POST로 생성한 이벤트를 GET 상세로 조회할 수 있다`() {
        // POST
        val postResult = mockMvc.post("/api/v1/events") {
            contentType = MediaType.APPLICATION_JSON
            content = EventFixture.createRequestJson()
        }.andExpect {
            status { isCreated() }
        }.andReturn()

        val created = objectMapper.readTree(postResult.response.contentAsString)
        val id = created["id"].asText()

        // GET 상세
        mockMvc.get("/api/v1/events/$id")
            .andExpect {
                status { isOk() }
                jsonPath("$.title") { value("여름 쿠폰 이벤트") }
            }
    }

    @Test
    fun `POST에 startedAt이 endedAt보다 이후면 400 INVALID_DATE_RANGE를 반환한다`() {
        val json = EventFixture.createRequestJson(
            startedAt = "2026-07-31T23:59:59Z",
            endedAt = "2026-07-01T00:00:00Z",
        )
        mockMvc.post("/api/v1/events") {
            contentType = MediaType.APPLICATION_JSON
            content = json
        }.andExpect {
            status { isBadRequest() }
            jsonPath("$.code") { value("C400-1") }
        }
    }

    @Test
    fun `GET events-id는 존재하지 않는 UUID에 대해 404 EVENT_NOT_FOUND를 반환한다`() {
        mockMvc.get("/api/v1/events/019644a2-0000-7000-a000-000000000000")
            .andExpect {
                status { isNotFound() }
                jsonPath("$.code") { value("E404") }
            }
    }

    @Test
    fun `여러 이벤트 생성 후 keyword 검색이 동작한다`() {
        listOf("여름 할인", "겨울 특가", "봄 프로모션").forEach { title ->
            mockMvc.post("/api/v1/events") {
                contentType = MediaType.APPLICATION_JSON
                content = EventFixture.createRequestJson(title = title)
            }.andExpect { status { isCreated() } }
        }

        mockMvc.get("/api/v1/events?keyword=여름")
            .andExpect {
                status { isOk() }
                jsonPath("$.content.length()") { value(1) }
            }
    }
}
```

**총 4개 테스트**.

---

## 8. Test Count Summary

| Layer | 파일 수 | 테스트 케이스 수 |
|-------|:---:|:---:|
| L1 Domain (6 files) | 6 | 10 + 10 + 12 + 3 + 3 + 6 = **44** |
| L2 Service (MockK) | 1 | **6** |
| L3 Slice (Repo + Controller) | 2 | 9 + 7 = **16** |
| L4 Integration | 1 | **4** |
| **Total** | **10 + Fixture** | **70** |

---

## 9. Dependency Mapping (FR-T → Test)

| FR ID | Requirement | 대응 Test |
|-------|-------------|----------|
| FR-T01 | DateRange 불변식 | `DateRangeTest` #2, #3 |
| FR-T02 | DateRange 경계값 | `DateRangeTest` #4~#10 |
| FR-T03 | EventStatus 전이 규칙 | `EventStatusTest` #4~#9 |
| FR-T04 | EventStatus.isIssuable | `EventStatusTest` #1~#3 |
| FR-T05 | Event.issue() 원인별 예외 | `EventTest` #6, #7 |
| FR-T06 | Event.open/close | `EventTest` #9~#12 |
| FR-T07 | EventQuery.orders 화이트리스트 | `EventQueryTest` #5, #6 |
| FR-T08 | EventService.create (Mockk) | `EventServiceTest` #1, #2 |
| FR-T09 | EventService.getEvent not found | `EventServiceTest` #4 |
| FR-T10 | EventService.getEvents 위임 | `EventServiceTest` #5, #6 |
| FR-T11 | EventQueryRepository 필터 | `EventQueryRepositoryTest` #1~#6 |
| FR-T12 | EventQueryRepository 페이지/정렬 | `EventQueryRepositoryTest` #7~#9 |
| FR-T13 | EventController Bean Validation | `EventControllerTest` #2, #3, #6 |
| FR-T14 | EventController Pageable 변환 | `EventControllerTest` #4 |
| FR-T15 | E2E POST→GET 상세 | `EventCrudIntegrationTest` #1 |
| FR-T16 | E2E INVALID_DATE_RANGE | `EventCrudIntegrationTest` #2 |
| FR-T17 | E2E EVENT_NOT_FOUND | `EventCrudIntegrationTest` #3 |

**17개 FR 모두 최소 1개 테스트로 커버**.

---

## 10. Implementation Order

Plan Step 순서를 재확인:

| Step | File | FR Covered |
|------|------|-----------|
| 1 | `support/EventFixture.kt` | — (기반) |
| 2 | `DateRangeTest.kt` | FR-T01, FR-T02 |
| 3 | `EventStatusTest.kt` | FR-T03, FR-T04 |
| 4 | `EventTest.kt` | FR-T05, FR-T06 |
| 5 | `EventCreateRequestTest.kt` | (보조) |
| 6 | `EventResponseTest.kt` | (보조) |
| 7 | `EventQueryTest.kt` | FR-T07 |
| 8 | `EventServiceTest.kt` | FR-T08~FR-T10 |
| 9 | `EventQueryRepositoryTest.kt` | FR-T11, FR-T12 |
| 10 | `EventControllerTest.kt` | FR-T13, FR-T14 |
| 11 | `EventCrudIntegrationTest.kt` | FR-T15~FR-T17 |

각 Step은 이전 Step이 완료되어야 다음 Step을 시작한다 (순서 의존). Fixture가 먼저 만들어져야 Domain 테스트가 이를 활용 가능.

---

## 11. Known Issues & Decisions Log

| 이슈 | 결정 | 근거 |
|-----|------|------|
| Kotest specs(FunSpec) 도입 여부 | **도입 안 함**. JUnit 5 `@Test` + Kotest assertions 혼용 | Spring 통합이 JUnit 5에서 더 자연스러움. 학습 곡선 ↓ |
| Kotest `kotest-runner-junit5` 포함 | 포함 (assertions만 쓰는데도) | 향후 선택적 Kotest specs 사용을 위한 여지 유지. 오버헤드 미미 |
| springmockk 도입 | **보류** | `spring-boot-dependencies:3.0.0` 기반이라 Spring Boot 4 호환성 리스크 |
| L3 Controller mock 라이브러리 | `@MockitoBean` + `mockito-kotlin` | Spring Boot 4 네이티브, 호환성 안전 |
| L2 Service의 DSL 일관성 | MockK + Kotest assertions (통일) | Kotlin-native 경험 |
| L3 어설션 | Kotest (`shouldBe`) + mock은 Mockito | 어설션만 통일, mock DSL은 계층별 다름 |
| `@DataJpaTest` 기본 H2 교체 | `@AutoConfigureTestDatabase(replace = NONE)` + Testcontainers Postgres | QueryDSL/Flyway가 Postgres-specific 기능을 사용하므로 H2 부적합 |
| Period 필터 테스트 (시간 기반) | Repository 테스트에서는 생략, 필요 시 `Clock` 주입 리팩토링 | Flaky 회피 |
| `EventQuery` where 조건 단위 테스트 | 직접 테스트 안 함, Repository 테스트에서 간접 검증 | `BooleanExpression` 동일성 비교가 어렵고 실용성 낮음 |
| Property-based testing | 도입 안 함 | YAGNI, CRUD 규모에 과함. 필요 시 나중에 `kotest-property` 추가 |

---

## 12. Success Criteria

- [ ] `EventFixture`가 작성되고 모든 테스트가 이를 사용한다
- [ ] 10개 테스트 파일이 모두 작성되고 `./gradlew test` 전체 통과
- [ ] L1 Domain 테스트(44개)가 **< 1초**에 실행된다
- [ ] L2 Service 테스트(6개)가 **< 2초**에 실행된다
- [ ] 17개 FR-T가 모두 테스트로 커버된다
- [ ] 모든 테스트 이름이 한국어 백틱 함수명이고 도메인 규칙을 설명한다
- [ ] Kotest assertions(`shouldBe`, `shouldThrow<T>`)가 모든 계층에서 일관되게 사용된다
- [ ] MockK는 L2에서만, Mockito는 L3 Controller에서만 사용된다 (계층별 명확한 책임)

---

## 13. Next Steps

1. [ ] Design 승인 후 `/pdca do event-crud-test`로 구현 단계 진입
2. [ ] Step 1 ~ 11 순서대로 파일 작성
3. [ ] 각 Step 완료 후 `./gradlew test --tests "<클래스명>"`로 개별 검증
4. [ ] 전체 작성 완료 후 `./gradlew test` 전체 통과 확인
5. [ ] `/pdca analyze event-crud-test`로 Design ↔ 구현 Gap 분석
6. [ ] Gap ≥ 90% 달성 시 `/pdca report event-crud-test`

---

## Version History

| Version | Date | Changes | Author |
|---------|------|---------|--------|
| 0.1 | 2026-04-10 | Initial draft — Plan v0.3 기반 상세 설계. 10개 테스트 파일 + EventFixture 구조/의존성/테스트 케이스 명세. 70개 테스트 케이스 정의. 17개 FR 매핑 완료. | beomjin |
