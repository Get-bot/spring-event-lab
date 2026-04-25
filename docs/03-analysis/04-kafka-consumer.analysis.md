# 04-kafka-consumer — Design-Implementation Gap Analysis

> **Feature**: 04-kafka-consumer
> **Date**: 2026-04-20
> **Design Doc**: [04-kafka-consumer.design.md](../02-design/features/04-kafka-consumer.design.md) (v0.1)
> **Analyst**: gap-detector agent
> **Match Rate**: **97%**
> **Status**: PASS — 모든 핵심 설계 결정 일치, Design 문서 기술 명세에 업데이트 부채(documentation debt)만 존재

---

## Executive Summary

| Perspective | Content |
|-------------|---------|
| **Problem** | Design v0.1은 Spring Kafka 2.x/3.x 시절 API(`JsonSerializer`, `org.springframework.retry.annotation.Backoff`, `backoff =`)로 작성됨. Spring Boot 4.0.5 / Spring Kafka 4.0.4 실제 환경에선 일부가 `@Deprecated(forRemoval=true)` 또는 제거됨 |
| **Solution** | 실 구현은 Spring Kafka 4.x 표준 API(`JacksonJsonSerializer`, `org.springframework.kafka.annotation.BackOff`, `backOff =`) 사용. 핵심 설계 결정(UUID v7 사전 생성, 동기 `send().get()`, partition key, RECORD ack, exclude UK violation)은 100% 보존 |
| **Function/UX Effect** | 유저 응답 흐름 / 에러 처리 / DLT 경로 / 멱등성 계약 모두 Design 의도 그대로 작동. 동시성 테스트 4개 TC가 Kafka 비동기 소비 대기 로직(`awaitDbCount`) 통합 후 통과 |
| **Core Value** | 실제 구현이 Design 대비 더 최신 API를 사용하는 "우호적 divergence" — 기능/의미 Gap은 0건. Design 문서 v0.2 업데이트로 documentation debt만 해결하면 됨 |

---

## 1. Analysis Scope

- **Design Document**: `docs/02-design/features/04-kafka-consumer.design.md` (v0.1, 2026-04-20)
- **Implementation Path**: `src/main/kotlin/com/beomjin/springeventlab/{coupon,global}/`
- **Analysis Date**: 2026-04-20 (Do 직후)

---

## 2. Success Criteria Verification (§ 10)

| # | Criteria | File | Status |
|---|----------|------|--------|
| 1 | `CouponIssueTxService` 삭제, `CouponIssueWriter.kt` 제거 | `coupon/service/` — `EventService.kt`, `CouponIssueService.kt`만 존재 | PASS |
| 2 | `CouponIssueProducer`가 발행 성공 시 201 응답 유지 | `CouponIssueService.kt` — publish 이후 `CouponIssueResponse` 반환 | PASS |
| 3 | Producer 발행 실패 시 Redis `compensate()` 호출 + 503 응답 | `CouponIssueProducer.kt` + `ErrorCodeMapper.kt` | PASS |
| 4 | `@RetryableTopic`이 retry 토픽 3개 + `.DLT` 자동 생성 | `CouponIssueConsumer.kt` (`autoCreateTopics = "true"`, `attempts = "4"`) | PASS |
| 5 | Consumer가 메시지를 수신해 `coupon_issue` 테이블에 INSERT | `CouponIssueConsumer.kt` | PASS |
| 6 | Consumer 중단 후 재시작 시 `auto-offset-reset=earliest`로 미소비 메시지 처리 | `application.yaml:39` | PASS |
| 7 | DB UK 위반(재소비) 시 Consumer가 ack (재시도 없음) | `CouponIssueConsumer.kt` `exclude = [DataIntegrityViolationException::class]` + `try/catch` | PASS |
| 8 | DLT 메시지는 `@DltHandler`에서 ERROR 로그 수신 | `CouponIssueConsumer.kt` | PASS |
| 9 | 기존 동시성 테스트가 Kafka Consumer 대기 로직 추가 후 통과 | `CouponIssueConcurrencyTest.kt` `awaitDbCount` 폴링 헬퍼, TC-01~04 전부 사용 | PASS |

**Result**: 9/9 충족 (100%)

---

## 3. Implementation Order Verification (§ 7)

| Step | Design 요구 | 실제 파일 | Status |
|------|------------|-----------|--------|
| 1 | `CouponIssueMessage.kt` (data class) | `coupon/dto/message/CouponIssueMessage.kt` | PASS |
| 2 | `ErrorCode.COUPON_PUBLISH_FAILED` + 503 매핑 | `ErrorCode.kt` (CI503) + `ErrorCodeMapper.kt` | PASS |
| 3 | `KafkaConfig.kt` (Producer/Consumer/Template + Topic) | `global/config/KafkaConfig.kt` — 단일 파일에 `couponIssueTopic()` 통합 | PASS |
| 4 | `CouponIssue.kt` 주 생성자 `id` 파라미터 추가 | `entity/CouponIssue.kt` | PASS |
| 5 | `CouponIssueProducer.kt` publish + compensate | `coupon/producer/CouponIssueProducer.kt` | PASS |
| 6 | `CouponIssueConsumer.kt` `@RetryableTopic` + `@DltHandler` | `coupon/consumer/CouponIssueConsumer.kt` | PASS |
| 7 | `CouponIssueService.kt` Producer 교체 + `issueId` 사전 생성 | `service/CouponIssueService.kt` | PASS |
| 8 | `CouponIssueWriter.kt` 삭제 | 파일 부재 확인 (grep 0 hits) | PASS |
| 9 | `application.yaml` JSON/ack-mode | `application.yaml:35-51` | PASS |

**Result**: 9/9 구현 (100%)

---

## 4. Core Design Decision Match

| # | Design Decision | Implementation | Status |
|---|----------------|----------------|--------|
| D1 | UUID v7 사전 생성 — 응답 id == 메시지 id | `CouponIssueService.kt` `UuidCreator.getTimeOrderedEpoch()` → `CouponIssueMessage(id=issueId)` → `CouponIssueResponse(id=issueId)` | PASS |
| D2 | `send().get(3000ms)` 동기 대기 | `CouponIssueProducer.kt` `.get(PUBLISH_TIMEOUT_MS=3000L, TimeUnit.MILLISECONDS)` | PASS |
| D3 | Partition key = `eventId.toString()` | `CouponIssueProducer.kt` `message.eventId.toString()` | PASS |
| D4 | `@RetryableTopic(attempts=4, delay=1000, multiplier=2.0, dltTopicSuffix=".DLT", exclude=DataIntegrityViolationException)` | `CouponIssueConsumer.kt` 전 필드 일치 + `topicSuffixingStrategy = SUFFIX_WITH_INDEX_VALUE` 추가 | PASS |
| D5 | `@Transactional` on Consumer method | `CouponIssueConsumer.kt` | PASS |
| D6 | `ackMode = RECORD` | `KafkaConfig.kt` + `application.yaml` (이중) | PASS |
| D7 | `JsonDeserializer TRUSTED_PACKAGES` 제한 (보안) | `KafkaConfig.kt` `JacksonJsonDeserializer.TRUSTED_PACKAGES = "com.beomjin.springeventlab.*"` | PASS |
| D8 | Producer 실패 시 `redisStockRepository.compensate()` + `COUPON_PUBLISH_FAILED` 전파 | `CouponIssueProducer.kt` | PASS |
| D9 | Consumer 내 `DataIntegrityViolationException` 로그 후 삼킴 (이중 방어) | `CouponIssueConsumer.kt` | PASS |

**Result**: 9/9 일치 (100%) — 8개 exact match, D4는 `topicSuffixingStrategy` 1개 추가 (retry 토픽 이름 결정론화)

---

## 5. Error Handling Matrix (§ 5) Verification

| # | Design 시나리오 | 실제 처리 경로 | Status |
|---|----------------|---------------|--------|
| E01 | Event 미존재 → `EVENT_NOT_FOUND` (404) | `CouponIssueService.kt` | PASS |
| E02 | 이벤트 시간 외 → `EVENT_NOT_OPEN` (409) | `CouponIssueService.kt` | PASS |
| E03 | Redis `initStockIfAbsent` 실패 → 예외 전파 | `CouponIssueService.kt` (try/catch 없음 → `GlobalExceptionHandler` 경유) | PASS |
| E04 | Lua -1 → `COUPON_ALREADY_ISSUED` (409) | `CouponIssueService.kt` | PASS |
| E05 | Lua 0 → `EVENT_SOLD_OUT` (410) | `CouponIssueService.kt` + `ErrorCodeMapper.kt` GONE | PASS |
| E06 | Lua 1 → Kafka publish 성공 → 201 | `CouponIssueService.kt` | PASS |
| E07 | Kafka publish 실패 → Redis 보상 + `COUPON_PUBLISH_FAILED` (503) | `CouponIssueProducer.kt` | PASS |
| E08 | Consumer UK 위반 → 로그 후 ack | `CouponIssueConsumer.kt` (exclude + try/catch 이중) | PASS |
| E09 | Consumer 기타 DB 예외 → retry 3회 → .DLT | `@RetryableTopic attempts="4"` + exclude 외 전파 | PASS |
| E10 | DLT → `@DltHandler` ERROR 로그 | `CouponIssueConsumer.kt` | PASS |
| E11 | Redis 장애 → `REDIS_UNAVAILABLE` (503) | `ErrorCode.kt` + `ErrorCodeMapper.kt` (02-redis-stock 유산) | PASS |

**Result**: 11/11 매칭 (100%)

---

## 6. Design vs Implementation Divergence (의도적 개선)

Design v0.1은 Spring Kafka 2.x/3.x API 전제, 실제 프로젝트는 Spring Boot 4.0.5 + Spring Kafka 4.0.4. 아래는 **deprecation/removal 대응** 또는 **프레임워크 분리(spring-retry detachment)** 로 인한 차이이며, Design 의도와 반대가 아닌 동일 의미의 최신 API 치환이다.

| # | Design (v0.1) | Implementation | 이유 | 분류 |
|---|---------------|----------------|------|------|
| G1 | `import ...support.serializer.JsonSerializer` | `JacksonJsonSerializer` | Spring Kafka 4.x에서 `JsonSerializer`는 `@Deprecated(forRemoval=true)`. Jackson 3 기반이 표준 | Documentation Debt |
| G2 | `...serializer.JsonDeserializer` | `JacksonJsonDeserializer` | 위와 동일. `TRUSTED_PACKAGES`, `VALUE_DEFAULT_TYPE` 상수도 새 클래스 사용 | Documentation Debt |
| G3 | `backoff = Backoff(...)` (`org.springframework.retry.annotation.Backoff`) | `backOff = BackOff(...)` (`org.springframework.kafka.annotation.BackOff`) | Spring Kafka 4에서 spring-retry 의존성 분리 + 필드명 `backoff` → `backOff`(카멜케이스) | Documentation Debt |
| G4 | `props.buildProducerProperties(null)` / `buildConsumerProperties(null)` | `props.buildProducerProperties()` / `buildConsumerProperties()` | Spring Boot 4에서 무인자 버전 제공 | Documentation Debt |
| G5 | `KafkaTopicConfig` 별도 `@Configuration` | `KafkaConfig` 내부 `couponIssueTopic()` Bean | 단일 파일로 응집. 동등 기능 | 의도적 단순화 |
| G6 | 상수 언급 없음 | `KafkaConfig.companion object { COUPON_ISSUE_TOPIC, COUPON_ISSUE_GROUP }` | 매직 스트링 제거. Producer/Consumer가 동일 상수 참조 | 개선(Enhancement) |
| G7 | `topicSuffixingStrategy` 언급 없음 | `SUFFIX_WITH_INDEX_VALUE` 지정 | retry 토픽 이름 결정론화 | 개선(Enhancement) |
| G8 | § 2.3 다이어그램의 `coupon-issue-retry-1000/2000/4000` | 실제 retry 토픽 naming은 `SUFFIX_WITH_INDEX_VALUE` 전략 기준 | G7의 side-effect로 문서 다이어그램 보정 필요 | Documentation Debt |

---

## 7. Match Rate Summary

```
┌─────────────────────────────────────────────────────┐
│  Overall Match Rate: 97%                             │
├─────────────────────────────────────────────────────┤
│  Success Criteria (§10):       9/9  = 100%           │
│  Implementation Order (§7):    9/9  = 100%           │
│  Core Design Decisions:        9/9  = 100%           │
│  Error Handling Matrix (§5):  11/11 = 100%           │
│  Core Match (기능/의미):              100%           │
│                                                      │
│  Documentation Debt (G1-G4, G8):       -3%           │
│  Enhancements (G5-G7): not penalized                 │
└─────────────────────────────────────────────────────┘
```

감점 3% 근거: Design 문서가 향후 다른 개발자에게 그대로 재현되면 **컴파일 에러**가 발생하는 코드 스니펫을 포함 (G1-G4). 기능 gap은 0이지만 재현성 관점의 documentation debt.

---

## 8. Convention Compliance

| Category | Rule (CLAUDE.md) | Observation | Status |
|----------|------------------|-------------|--------|
| Entity | 주 생성자 + `protected set` var | `CouponIssue.kt` 패턴 준수 | PASS |
| Entity | `@JdbcTypeCode(SqlTypes.UUID)` | `CouponIssue.kt` | PASS |
| ErrorCode | `{DOMAIN}_{CONDITION}` + 서브코드 `CI503` | `COUPON_PUBLISH_FAILED("CI503", ...)` | PASS |
| Package | `coupon.producer`, `coupon.consumer`, `coupon.dto.message` | Design § 6 File Structure 일치 | PASS |
| Logging | `io.github.oshai.kotlinlogging.KotlinLogging` top-level `log` | Producer/Consumer 모두 패턴 준수 | PASS |

---

## 9. Recommended Actions

### 9.1 Immediate (Design doc v0.2 업데이트)

| Priority | Item | Design Location | Action |
|----------|------|-----------------|--------|
| 1 | G1-G2 반영 | § 3.2 `KafkaConfig` 코드 블록 | `JsonSerializer` → `JacksonJsonSerializer`, `JsonDeserializer` → `JacksonJsonDeserializer` |
| 2 | G3 반영 | § 3.5 `CouponIssueConsumer` 코드 블록 | `backoff = Backoff(...)` → `backOff = BackOff(...)` (import도 `org.springframework.kafka.annotation.BackOff`) |
| 3 | G4 반영 | § 3.2 | `props.buildProducerProperties(null)` → `props.buildProducerProperties()` |
| 4 | G8 side-effect | § 2.3 Data Flow 다이어그램 | retry 토픽 naming 보정 또는 `TopicSuffixingStrategy` 명시 |
| 5 | § 3.3 통합 | 단일 `KafkaConfig` 반영 | `KafkaTopicConfig` 별도 기술 제거 후 `KafkaConfig.couponIssueTopic()`으로 합치기 |

### 9.2 Short-term (선택적 Design 보강)

- Topic/Group 상수 companion pattern을 Design Principle로 승격
- `TopicSuffixingStrategy.SUFFIX_WITH_INDEX_VALUE` 선택 근거 문서화 (운영 시 retry 토픽 모니터링 query 작성에 영향)

### 9.3 Long-term (backlog)

- Version Bump: Design v0.1 → v0.2 (Spring Boot 4 / Spring Kafka 4 API 반영)

---

## 10. Next Steps

- Match Rate **97% ≥ 90%** → **Report 단계 진입 가능** (`/pdca report 04-kafka-consumer`)
- `/pdca iterate` 불필요 (기능 Gap 0, Design doc 업데이트는 Report 병기로 충분)
- Report 작성 시 본 analysis의 Documentation Debt 섹션을 "Lessons Learned"에 포함 → 다음 feature (`05-waiting-queue`) Design 작성 시 Spring Kafka 4.x API를 처음부터 사용하도록 가이드

---

## Version History

| Version | Date | Changes | Author |
|---------|------|---------|--------|
| 0.1 | 2026-04-20 | Initial Gap analysis — Match Rate 97%, Documentation Debt 위주 | gap-detector (via /pdca analyze) |
