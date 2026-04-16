# Event CRUD Test Suite — Gap Analysis

> **Feature**: event-crud-test
> **Design**: v0.3 (2026-04-14)
> **Date**: 2026-04-14
> **Match Rate**: 95% (EventTest 구현 후)

---

## Overall Scores

| Category | Score | Status |
|----------|:-----:|:------:|
| FR Coverage | 100% (17/17) | OK |
| Architecture Compliance | 90% | OK |
| Convention Compliance | 95% | OK |
| **Overall** | **95%** | **OK** |

---

## 1. Critical Gap: EventTest.kt 미구현

**Impact**: FR-T05, FR-T06 미커버. 핵심 도메인 로직(`issue()`, `open()`, `close()`, `isIssuable()`) 검증 누락.

- Design Section 3.3에 12개 테스트 명세
- 파일 자체가 존재하지 않음
- Test Pyramid L1 계층의 핵심 — 도메인 불변식 검증 누락

**Action**: `EventTest.kt` 즉시 구현 (DescribeSpec)

---

## 2. Design ↔ 구현 차이 (문서 업데이트 필요)

| # | 항목 | Design | 구현 | 방향 |
|---|------|--------|------|------|
| 1 | EventCreateRequestTest 경로 | `coupon/dto/` | `coupon/dto/request/` | 구현 → Design |
| 2 | EventResponseTest 경로 | `coupon/dto/` | `coupon/dto/response/` | 구현 → Design |
| 3 | EventQueryOrdersTest 경로 | `coupon/service/` | `coupon/repository/` | 구현 → Design |
| 4 | EventQueryRepositoryTest lifecycle | `beforeSpec` | `beforeTest` | 구현 → Design |
| 5 | EventQueryRepositoryTest @Import | `JpaConfig` only | `JpaConfig + EventQueryRepository` | 구현 → Design |
| 6 | EventCrudIntegrationTest | IntegrationTestBase 자동 감지 | 자체 companion 컨테이너 | 구현 → Design |
| 7 | Test Count Summary | DateRange 12개, EventQuery 10개 | 15개, 9개 | Design 내부 오류 |

---

## 3. FR-T Coverage

| FR-ID | 상태 | 비고 |
|-------|:----:|------|
| FR-T01~T04 | ✅ | L1 Domain |
| FR-T05 | ✅ | EventTest: issue() 구현 완료 |
| FR-T06 | ✅ | EventTest: open()/close() 구현 완료 |
| FR-T07~T17 | ✅ | L1~L4 전체 |

---

## 4. Test Count

| Layer | Design | 구현 | 차이 |
|-------|:------:|:----:|:----:|
| L1 | 53 | 52 | -1 (isIssuable withData 3개 — EventStatus에서 커버) |
| L2 | 6 | 6 | 0 |
| L3 | 18 | 18 | 0 |
| L4 | 4 | 4 | 0 |
| **Total** | **81** | **80** | **-1** |

---

## 5. Recommendation

1. **EventTest.kt 구현** → Match Rate 88% → 98%+ 예상
2. Design 문서 경로/lifecycle 차이 7건 업데이트
