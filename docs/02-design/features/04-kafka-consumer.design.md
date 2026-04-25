# Kafka Consumer Design Document

> **Summary**: Redis 재고 확보 성공 → Kafka Producer 즉시 응답 → Consumer가 DB 소화 속도로 비동기 저장. Peak Load Shifting의 구체적 기술 명세
>
> **Project**: spring-event-lab
> **Version**: 0.0.1-SNAPSHOT
> **Author**: beomjin
> **Date**: 2026-04-20
> **Status**: Draft
> **Planning Doc**: [04-kafka-consumer.plan.md](../../01-plan/features/04-kafka-consumer.plan.md)
> **Depends On**: 02-redis-stock (`CouponIssueService`, `RedisStockRepository`, `CouponIssue` 엔티티)

---

## Executive Summary

| Perspective | Content |
|-------------|---------|
| **Problem** | redis-stock 구현은 Redis 성공 직후 `@Transactional`로 DB INSERT를 수행 → 순간 1만 TPS 스파이크 시 HikariCP 풀이 고갈되고 응답 지연 발생 |
| **Solution** | `CouponIssueTxService`를 `CouponIssueProducer`로 교체해 Kafka `coupon-issue` 토픽에 발행만 수행. `CouponIssueConsumer`가 `@RetryableTopic`으로 DB 속도에 맞춰 소비하며 실패 시 `.DLT`로 격리 |
| **Function/UX Effect** | 유저 응답 지연은 Redis Lua 1 RTT + Kafka produce 1 RTT로 고정. DB INSERT는 유저 경로에서 완전히 제거 |
| **Core Value** | UUID v7을 Producer에서 미리 생성해 **즉시 201 Created + CouponIssueResponse** 응답을 유지하면서, 10,000 TPS 스파이크를 DB 500 TPS로 평탄화하는 Peak Load Shifting 패턴을 학습 |

---

## 1. Overview

### 1.1 Design Goals

- **유저 경로에서 DB 제거**: `CouponIssueService.issue()`는 Redis + Kafka produce만 수행. DB 커넥션을 점유하지 않음
- **응답 계약 유지**: 기존 201 Created + `CouponIssueResponse`(id 포함)를 그대로 반환. Producer에서 UUID v7을 미리 생성
- **선언적 DLT 처리**: `@RetryableTopic` + `@DltHandler`로 재시도/DLT 경로를 어노테이션으로 표현. 수동 `DefaultErrorHandler`를 피함
- **Consumer 멱등성**: DB `uk_coupon_issue(event_id, user_id)` 제약을 최종 방어선으로 유지. `DataIntegrityViolationException` catch 시 로그 후 ack (메시지 중복 소비를 정상 종료로 처리)
- **기존 테스트 유지**: 동시성 테스트가 Redis 건수 검증 + Consumer 소비 완료 후 DB 검증으로 전환되어 통과

### 1.2 Design Principles

- **Kafka는 DB 쓰기 버퍼**: Kafka 파티션을 DB 커넥션 풀 앞의 쿠션으로 사용. 파티션 수 ≥ Consumer 수로 병렬 소비
- **Producer에서 ID 확정**: UUID v7이 시간 순서성을 보장하므로 Producer에서 생성해도 정렬 문제 없음. Consumer는 메시지의 id로 `CouponIssue`를 생성 → 응답 일관성 + 멱등성
- **Eventually Consistent**: 유저 응답(즉시) ≫ DB 반영(수 초 후). Redis가 진실의 원천, DB는 이벤트 소싱 기록
- **실패 위치별 분리 전략**:
  - Producer 실패 (최대 `retries=3` 후 실패) → Redis 보상 (SREM + INCR) + 500 응답
  - Consumer 실패 (3회 재시도 후) → DLT. Redis는 건드리지 않음 (이미 유저에겐 성공 통지)

---

## 2. Architecture

### 2.1 Component Diagram

```
┌──────────────┐     ┌─────────────────────────────────────────────────────────┐     ┌──────────────┐
│              │     │                   Spring Boot App                       │     │              │
│   Client     │────▶│  CouponIssueController                                 │     │  Redis 8     │
│ (Swagger UI) │     │      ↓                                                 │────▶│  (Lua EVAL)  │
│              │     │  CouponIssueService (orchestrator, no @Transactional)  │     │              │
│              │     │   ↓            ↓                                        │     └──────────────┘
│              │     │ EventRepo   RedisStock   CouponIssueProducer           │     ┌──────────────┐
│              │     │ (read-only)  Repository  (NEW, replaces TxService)     │────▶│   Kafka      │
│              │     │                ↓                                        │     │ coupon-issue │
│              │     │              KafkaTemplate<String, CouponIssueMessage> │     │ (partitions) │
│              │     │                                                        │     └──────┬───────┘
│              │     │  CouponIssueConsumer (NEW, @RetryableTopic)            │            │
│              │     │   ↓                                                    │            │
│              │     │  CouponIssueRepository (JPA save)                      │◀───────────┘
│              │     │   ↓                                                    │     ┌──────────────┐
│              │◀────│  GlobalExceptionHandler                                │────▶│  PostgreSQL  │
│              │     └─────────────────────────────────────────────────────────┘     │  coupon_issue│
└──────────────┘                                                                      └──────────────┘
```

### 2.2 Data Flow — 쿠폰 발급 (동기 구간)

```
POST /api/v1/events/{eventId}/issue?userId={userId}

1. [Controller] CouponIssueController.issue(eventId, userId)

2. [Service] 시간 검증
   eventRepository.findByIdOrNull(eventId)
   → event.period.contains(Instant.now())
   └─ false → EVENT_NOT_OPEN (409)

3. [Service] Redis 재고 Lazy Init
   redisStockRepository.initStockIfAbsent(eventId, totalQuantity, ttlSeconds)

4. [Service] Lua 스크립트 실행
   redisStockRepository.tryIssueCoupon(eventId, userId, ttlSeconds)
   ├─ -1 → COUPON_ALREADY_ISSUED (409)
   ├─  0 → EVENT_SOLD_OUT (410)
   └─  1 → 성공

5. [Service → Producer] Kafka 발행 (동기 대기)
   val issueId = UuidCreator.getTimeOrderedEpoch()
   val message = CouponIssueMessage(issueId, eventId, userId, Instant.now())
   couponIssueProducer.publish(message).get(timeout)
   ├─ Kafka 발행 실패 (retries 소진) → Redis 완전 보상(SREM+INCR) + 500 응답
   └─ 성공 → 201 Created + CouponIssueResponse(issueId, eventId, userId, issuedAt)
```

### 2.3 Data Flow — DB 반영 (비동기 구간)

```
[Kafka topic: coupon-issue]
      │
      ▼
6. [Consumer] @RetryableTopic + @KafkaListener
   CouponIssueConsumer.consume(message)
   → couponIssueRepository.save(CouponIssue(id=message.id, eventId, userId))
   ├─ 성공 → manual commit (ack)
   ├─ DataIntegrityViolationException (UK 위반) → 중복 메시지, 로그 후 ack (재시도 안 함)
   └─ 기타 DB 예외 → @RetryableTopic이 retry topic으로 발행
                    ├─ 1차: coupon-issue-retry-1000 (1s 대기)
                    ├─ 2차: coupon-issue-retry-2000 (2s)
                    ├─ 3차: coupon-issue-retry-4000 (4s)
                    └─ 4차 실패 → coupon-issue.DLT 로 이동

7. [DLT Handler] @DltHandler
   CouponIssueConsumer.handleDlt(message, exception)
   → ERROR 레벨 로그 + 메트릭 카운터 증가 (수동 조치 대기)
```

### 2.4 Dependencies

| 신규/변경 컴포넌트 | 의존하는 기존 컴포넌트 | 용도 |
|--------------------|----------------------|------|
| `CouponIssueProducer` (NEW) | `KafkaTemplate<String, CouponIssueMessage>` | Kafka 발행 |
| `CouponIssueProducer` | `RedisStockRepository` | 발행 실패 시 보상 |
| `CouponIssueConsumer` (NEW) | `CouponIssueRepository` (기존) | DB 저장 |
| `CouponIssueMessage` (NEW) | — | 메시지 스키마 (data class) |
| `KafkaConfig` (NEW) | `spring-boot-starter-kafka` | `ProducerFactory`, `ConsumerFactory`, `KafkaTemplate` 타입 고정 |
| `CouponIssueService` (MODIFY) | `CouponIssueProducer` | `CouponIssueTxService` 의존성 교체 |
| `CouponIssueTxService` (DELETE) | — | DB 쓰기가 Consumer로 이동하여 제거 |
| `CouponIssueResponse` (유지) | — | 응답 DTO 계약 변경 없음 (id를 Producer에서 확정) |
| `ErrorCode` (MODIFY) | — | +1 값 (`COUPON_PUBLISH_FAILED`) |

---

## 3. Detailed Design

### 3.1 CouponIssueMessage — Kafka 메시지 스키마

```kotlin
package com.beomjin.springeventlab.coupon.dto.message

data class CouponIssueMessage(
    val id: UUID,
    val eventId: UUID,
    val userId: UUID,
    val issuedAt: Instant,
)
```

**설계 결정 — id를 메시지에 포함**:

- Producer에서 `UuidCreator.getTimeOrderedEpoch()`로 **id를 미리 생성**하여 메시지와 응답에 동일 id를 사용
- Consumer가 재시도/재처리되어도 동일한 id로 `save()` → DB UK 제약 + 엔티티 `@Id`가 이중 방어
- 이유:
  - 유저는 응답 즉시 `couponIssueId`를 확인해야 함 (추후 조회 API 대비)
  - UUID v7의 시간순서성은 Producer/Consumer 어디서 생성하든 보존됨
  - Consumer 생성으로 위임하면 재시도마다 id가 달라져 멱등성 깨짐

**Jackson 직렬화**: `jackson-module-kotlin`이 이미 의존성에 있어 `data class` 그대로 직렬화됨. `Instant`는 JSR-310 모듈 자동 등록으로 ISO-8601 처리.

### 3.2 KafkaConfig

```kotlin
package com.beomjin.springeventlab.global.config

@Configuration
class KafkaConfig {

    @Bean
    fun producerFactory(props: KafkaProperties): ProducerFactory<String, CouponIssueMessage> {
        val config = props.buildProducerProperties(null).toMutableMap()
        config[ProducerConfig.KEY_SERIALIZER_CLASS_CONFIG] = StringSerializer::class.java
        config[ProducerConfig.VALUE_SERIALIZER_CLASS_CONFIG] = JsonSerializer::class.java
        return DefaultKafkaProducerFactory(config)
    }

    @Bean
    fun kafkaTemplate(
        producerFactory: ProducerFactory<String, CouponIssueMessage>,
    ): KafkaTemplate<String, CouponIssueMessage> = KafkaTemplate(producerFactory)

    @Bean
    fun consumerFactory(props: KafkaProperties): ConsumerFactory<String, CouponIssueMessage> {
        val config = props.buildConsumerProperties(null).toMutableMap()
        config[ConsumerConfig.KEY_DESERIALIZER_CLASS_CONFIG] = StringDeserializer::class.java
        config[ConsumerConfig.VALUE_DESERIALIZER_CLASS_CONFIG] = JsonDeserializer::class.java
        config[JsonDeserializer.TRUSTED_PACKAGES] = "com.beomjin.springeventlab.*"
        config[JsonDeserializer.VALUE_DEFAULT_TYPE] = CouponIssueMessage::class.java.name
        return DefaultKafkaConsumerFactory(config)
    }

    @Bean
    fun kafkaListenerContainerFactory(
        consumerFactory: ConsumerFactory<String, CouponIssueMessage>,
    ): ConcurrentKafkaListenerContainerFactory<String, CouponIssueMessage> =
        ConcurrentKafkaListenerContainerFactory<String, CouponIssueMessage>().apply {
            this.consumerFactory = consumerFactory
            containerProperties.ackMode = ContainerProperties.AckMode.RECORD  // 레코드 단위 수동 커밋
        }
}
```

**설계 결정**:
- `KafkaTemplate<String, CouponIssueMessage>`로 **타입 고정** → Producer 호출부에서 타입 안전성 확보 (`Any` 사용 금지)
- `JsonDeserializer`의 `TRUSTED_PACKAGES`를 명시적으로 제한 → RCE 위험 차단
- `ackMode = RECORD`: `@RetryableTopic`이 retry 토픽 발행을 완료한 뒤 원본 레코드를 자동 ack 하도록 레코드 단위 커밋 사용 (`enable-auto-commit=false`는 이미 application.yaml에 설정됨)
- `KafkaProperties`의 모든 설정(`acks=all`, `idempotence=true`, `retries=3`, `auto-offset-reset=earliest`)은 `application.yaml`에서 주입되므로 Config에서는 직렬화만 덮어씀

### 3.3 Topic 자동 생성

```kotlin
@Configuration
class KafkaTopicConfig {
    @Bean
    fun couponIssueTopic(): NewTopic =
        TopicBuilder.name("coupon-issue").partitions(3).replicas(1).build()
}
```

**설계 결정**: `KafkaAdmin`이 부팅 시 토픽을 생성. `@RetryableTopic`은 retry/DLT 토픽을 자체 생성하므로 `coupon-issue`만 명시.

### 3.4 CouponIssueProducer

```kotlin
package com.beomjin.springeventlab.coupon.producer

@Component
class CouponIssueProducer(
    private val kafkaTemplate: KafkaTemplate<String, CouponIssueMessage>,
    private val redisStockRepository: RedisStockRepository,
) {
    /**
     * 쿠폰 발급 메시지를 Kafka에 발행한다.
     * 발행 실패(retries 소진) 시 Redis 상태를 완전 보상하고 예외를 전파한다.
     * key는 eventId 문자열 — 같은 이벤트는 같은 파티션으로 라우팅되어 소비 순서 보장.
     */
    fun publish(message: CouponIssueMessage) {
        try {
            kafkaTemplate
                .send("coupon-issue", message.eventId.toString(), message)
                .get(PUBLISH_TIMEOUT_MS, TimeUnit.MILLISECONDS)
        } catch (e: Exception) {
            log.error(e) {
                "Kafka 발행 실패, Redis 보상 — eventId=${message.eventId}, userId=${message.userId}"
            }
            redisStockRepository.compensate(message.eventId, message.userId)
            throw BusinessException(ErrorCode.COUPON_PUBLISH_FAILED)
        }
    }

    companion object {
        private const val PUBLISH_TIMEOUT_MS = 3000L
    }
}
```

**설계 결정 — send().get() 동기 대기**:

- `acks=all` + `idempotence=true` + `retries=3`으로도 최종 실패 가능 (브로커 전체 장애, 타임아웃). 이 경우 유저에게 `201`을 응답하면 Redis 재고는 차감됐지만 DB엔 영원히 반영 안 되는 상태 → `get()`으로 ack 확인 후 응답
- `PUBLISH_TIMEOUT_MS = 3000ms`: redis 1 RTT (5~20ms) + Kafka produce (통상 5~50ms)를 고려한 여유 타임아웃
- 실패 시 `RedisStockRepository.compensate()` 호출 → SREM + INCR (기존 API 재활용)
- Partition key = `eventId.toString()`: 같은 이벤트의 메시지는 같은 파티션에 모여 Consumer 처리 순서가 재현 가능 (동시성 테스트 로그 검증 용이)

### 3.5 CouponIssueConsumer

```kotlin
package com.beomjin.springeventlab.coupon.consumer

@Component
class CouponIssueConsumer(
    private val couponIssueRepository: CouponIssueRepository,
) {
    @RetryableTopic(
        attempts = "4",                                          // 최초 1회 + 재시도 3회
        backoff = Backoff(delay = 1000, multiplier = 2.0),       // 1s, 2s, 4s
        dltTopicSuffix = ".DLT",
        exclude = [DataIntegrityViolationException::class],      // UK 위반은 재시도 없이 즉시 ack
        autoCreateTopics = "true",
    )
    @KafkaListener(topics = ["coupon-issue"], groupId = "spring-event-lab")
    @Transactional
    fun consume(message: CouponIssueMessage) {
        try {
            couponIssueRepository.save(
                CouponIssue(id = message.id, eventId = message.eventId, userId = message.userId)
            )
        } catch (e: DataIntegrityViolationException) {
            // 중복 소비(재처리, 동일 (eventId, userId) UK 충돌) → 정상 종료
            log.info { "중복 메시지 소비, 무시 — id=${message.id} (${e.javaClass.simpleName})" }
            // 예외를 삼켜 ack 처리 (exclude로도 재시도 차단되지만 로그 명시)
        }
    }

    @DltHandler
    fun handleDlt(
        @Payload message: CouponIssueMessage,
        @Header(KafkaHeaders.EXCEPTION_MESSAGE) reason: String,
    ) {
        log.error {
            "DLT 수신 — id=${message.id}, eventId=${message.eventId}, userId=${message.userId}, reason=$reason"
        }
        // TODO: 모니터링 카운터/알림 연동 (waiting-queue 이후 phase)
    }
}
```

**설계 결정 — `CouponIssue` 엔티티 id 사전 주입**:

현재 `CouponIssue` 엔티티는 생성자에서 `UuidCreator.getTimeOrderedEpoch()`로 id를 자동 생성한다. Consumer가 재시도 시마다 id가 달라지면 멱등성이 깨진다.

→ 엔티티를 수정: `id` 파라미터를 **선택적으로 주입** 가능하게 변경 (아래 3.6 참조).

**설계 결정 — `@Transactional` on listener method**:

Spring Kafka는 리스너 메서드에 `@Transactional`을 허용한다. DB 커밋 성공 후 ack가 진행되며, 예외 발생 시 트랜잭션 롤백 + `@RetryableTopic`이 retry 토픽으로 발행. `RECORD` ackMode이므로 offset 커밋은 `@RetryableTopic` 내부 로직이 관장.

**설계 결정 — `exclude = [DataIntegrityViolationException]`**:

UK 위반은 재시도해도 결과가 같으므로 즉시 ack. 이는 Consumer **멱등성의 핵심**. 그 외 DB 타임아웃/커넥션 오류는 재시도 가능.

### 3.6 CouponIssue 엔티티 수정

```kotlin
@Entity
@Table(name = "coupon_issue")
class CouponIssue(
    id: UUID = UuidCreator.getTimeOrderedEpoch(),  // 기본값으로 생성, Consumer에선 메시지 id 주입
    eventId: UUID,
    userId: UUID,
) : BaseCreatedTimeEntity() {
    @Id
    @JdbcTypeCode(SqlTypes.UUID)
    @Column(updatable = false, nullable = false, comment = "쿠폰_발급 PK")
    var id: UUID = id
        protected set

    // eventId, userId 동일 — 생략
}
```

**변경 포인트**: 주 생성자 `id` 파라미터 추가 + 기본값. 기존 호출부(`CouponIssue(eventId, userId)`)는 그대로 컴파일되므로 하위 호환.

### 3.7 CouponIssueService 변경

```kotlin
@Service
class CouponIssueService(
    private val eventRepository: EventRepository,
    private val redisStockRepository: RedisStockRepository,
    private val couponIssueProducer: CouponIssueProducer,   // CHANGED: TxService → Producer
) {
    fun issue(eventId: UUID, userId: UUID): CouponIssueResponse {
        // 1~4: Event 조회/시간 검증/Redis Lua — 기존과 동일

        // 5. Producer 발행 (id 사전 생성 → 메시지 & 응답에 동일 id 사용)
        val issueId = UuidCreator.getTimeOrderedEpoch()
        val now = Instant.now()
        couponIssueProducer.publish(
            CouponIssueMessage(id = issueId, eventId = eventId, userId = userId, issuedAt = now)
        )

        return CouponIssueResponse(
            id = issueId,
            eventId = eventId,
            userId = userId,
            createdAt = now,
        )
    }
}
```

**설계 결정**:
- `CouponIssueTxService` 의존성 완전 제거 → 클래스 삭제
- `CouponIssueResponse`는 **Consumer DB 저장을 기다리지 않음**. `id`, `createdAt`은 Producer가 결정. DB의 `created_at`(`BaseCreatedTimeEntity`)은 Consumer 실행 시점으로 별도 기록되므로 응답의 `createdAt`과 DB의 `createdAt`에 수 초 차이가 존재함 — 학습 포인트로 문서화

### 3.8 application.yaml 추가

```yaml
spring:
  kafka:
    consumer:
      properties:
        spring.json.trusted.packages: com.beomjin.springeventlab.*
        spring.json.value.default.type: com.beomjin.springeventlab.coupon.dto.message.CouponIssueMessage
    listener:
      ack-mode: record
    producer:
      properties:
        spring.json.add.type.headers: false   # Consumer가 default-type으로 역직렬화
```

`KafkaConfig`의 직렬화 설정과 중복되지 않도록 — YAML로만 표현 가능한 값은 YAML, 타입 안전이 필요한 `KafkaTemplate` Bean은 `KafkaConfig`에 둔다.

### 3.9 ErrorCode 추가

```kotlin
// Kafka Errors
COUPON_PUBLISH_FAILED("CI503", "쿠폰 발급 메시지 전송에 실패했습니다. 잠시 후 다시 시도해주세요."),
```

HTTP 503 매핑 (`ErrorCodeMapper`에 추가).

---

## 4. Topic & Message Lifecycle

```
[이벤트 생성]                    [쿠폰 발급 요청]                       [Consumer 처리]
     │                                  │                                    │
     ▼                                  ▼                                    ▼
(Kafka admin이                  Redis Lua 성공                      DB INSERT 성공
 coupon-issue 생성)              ↓                                   ↓
                                CouponIssueProducer.publish()         manual ack
                                 ↓                                   ↓
                                coupon-issue 토픽                    offset commit
                                 (partition = hash(eventId) % 3)
                                 ↓
                                Consumer poll
                                 ↓
                                [실패 시] @RetryableTopic
                                 ↓
                                coupon-issue-retry-1000 → -2000 → -4000
                                 ↓ (4회 실패)
                                coupon-issue.DLT
                                 ↓
                                @DltHandler 로깅
```

---

## 5. Error Handling Matrix

| 실패 지점 | Redis 상태 | DB 상태 | 처리 |
|-----------|------------|---------|------|
| Event 미존재 | — | — | `EVENT_NOT_FOUND` (404) |
| 이벤트 시간 외 | — | — | `EVENT_NOT_OPEN` (409) |
| Redis `initStockIfAbsent` 실패 | — | — | 예외 전파 (500) |
| Lua 반환 -1 (중복) | 변경 없음 | — | `COUPON_ALREADY_ISSUED` (409) |
| Lua 반환 0 (매진) | 변경 없음 | — | `EVENT_SOLD_OUT` (410) |
| Lua 반환 1 → Kafka publish 성공 | ✅ | ⏳ 비동기 | `201 Created` |
| Kafka publish 타임아웃/실패 | ✅ → **보상(SREM+INCR)** | ❌ | `COUPON_PUBLISH_FAILED` (503) |
| Consumer DB UK 위반 | ✅ | ❌ (이미 존재) | 로그 후 ack (정상) |
| Consumer 기타 DB 예외 | ✅ | ❌ | retry 토픽 (3회) → `.DLT` |
| DLT 도달 | ✅ | ❌ (영원히) | `@DltHandler` 로그 — **수동 조치 대상** |
| Redis 장애 | ❌ | — | `REDIS_UNAVAILABLE` (503) |

**중요 불변식**: 유저에게 `201`을 응답한 메시지는 반드시 (a) DB에 저장되었거나, (b) DLT에 격리되어 있다. 이 둘의 합이 Redis 발급 건수와 일치해야 한다.

---

## 6. File Structure (신규/변경)

```
src/main/
├── kotlin/com/beomjin/springeventlab/
│   ├── coupon/
│   │   ├── consumer/
│   │   │   └── CouponIssueConsumer.kt            ← NEW
│   │   ├── producer/
│   │   │   └── CouponIssueProducer.kt            ← NEW
│   │   ├── dto/
│   │   │   └── message/
│   │   │       └── CouponIssueMessage.kt         ← NEW
│   │   ├── entity/
│   │   │   └── CouponIssue.kt                    ← MODIFY (id 파라미터 추가)
│   │   └── service/
│   │       ├── CouponIssueService.kt             ← MODIFY (Producer 의존성)
│   │       └── CouponIssueWriter.kt              ← DELETE
│   └── global/
│       ├── config/
│       │   └── KafkaConfig.kt                    ← NEW (KafkaTopicConfig 포함)
│       └── exception/
│           ├── ErrorCode.kt                      ← MODIFY (+COUPON_PUBLISH_FAILED)
│           └── ErrorCodeMapper.kt                ← MODIFY (503 매핑)
└── resources/
    └── application.yaml                           ← MODIFY (Kafka JSON 직렬화/listener ack-mode)
```

---

## 7. Implementation Order

| Step | File | Description | Depends On |
|------|------|-------------|------------|
| 1 | `CouponIssueMessage.kt` | 메시지 스키마 data class | — |
| 2 | `ErrorCode.kt` + `ErrorCodeMapper.kt` | `COUPON_PUBLISH_FAILED` 추가 + 503 매핑 | — |
| 3 | `KafkaConfig.kt` | ProducerFactory/ConsumerFactory/Template Bean + Topic 선언 | Step 1 |
| 4 | `CouponIssue.kt` | 주 생성자에 `id` 파라미터 추가 (기본값 유지) | — |
| 5 | `CouponIssueProducer.kt` | publish() + 발행 실패 시 compensate() | Step 1, 3 |
| 6 | `CouponIssueConsumer.kt` | `@RetryableTopic` + `@DltHandler` | Step 1, 3, 4 |
| 7 | `CouponIssueService.kt` | Producer로 교체, `issueId` 사전 생성 | Step 1, 5 |
| 8 | `CouponIssueWriter.kt` **삭제** | DB 쓰기가 Consumer로 이동 | Step 7 |
| 9 | `application.yaml` | JSON 직렬화/ack-mode 보완 | Step 3 |

---

## 8. API Specification

### POST /api/v1/events/{eventId}/issue

엔드포인트/응답 계약은 redis-stock과 **동일**. 동작 의미가 달라짐:

| Item | redis-stock | kafka-consumer |
|------|-------------|----------------|
| DB 반영 시점 | 응답 전 (동기) | 응답 후 수 초 (비동기) |
| `CouponIssueResponse.createdAt` | DB `created_at` | Producer가 발행한 `issuedAt` |
| 신규 에러 | — | 503 `COUPON_PUBLISH_FAILED` (Kafka 최종 실패) |

나머지 필드/에러 코드는 redis-stock Design 문서 § 8 참조.

---

## 9. Testing Strategy

현행 테스트 자산에 미치는 영향:

| 테스트 계층 | 현재 | kafka-consumer 이후 |
|-------------|------|-------------------|
| L2 Service 단위 (`CouponIssueService`) | `CouponIssueTxService` mock | `CouponIssueProducer` mock — `publish()` 호출 검증 |
| L2 Producer 단위 | — | `KafkaTemplate` mock, 실패 시 `redisStockRepository.compensate()` 호출 검증 |
| L3 Consumer Slice | — | `@EmbeddedKafka` + `@SpringBootTest` — 메시지 publish → DB save 검증, UK 위반 시 ack 검증 |
| L4 Integration (concurrency-test) | Redis 건수 = DB 건수 즉시 비교 | **Kafka consumer latch로 소비 완료 대기** → 동일 비교. `KafkaListener` 메시지 카운트를 `CountDownLatch`로 기다림 |

**주의 — concurrency-test 회귀**:
- 기존 `docs/archive/2026-04/concurrency-test/` 테스트는 Redis→DB가 동기라는 전제 → Producer 교체 후 DB 카운트 단언 직전에 Consumer 완료를 기다리는 대기 구간 필요
- Testcontainers Kafka 컨테이너 추가 (`testcontainers-kafka` 의존성은 이미 있음)

---

## 10. Success Criteria

- [ ] `CouponIssueTxService` 삭제, `CouponIssueWriter.kt` 제거
- [ ] `CouponIssueProducer`가 발행 성공 시 201 응답 유지
- [ ] Producer 발행 실패 시 Redis `compensate()` 호출 + 503 응답
- [ ] `@RetryableTopic`이 retry 토픽 3개 + `.DLT`를 자동 생성
- [ ] Consumer가 메시지를 수신해 `coupon_issue` 테이블에 INSERT
- [ ] Consumer 중단 후 재시작 시 `auto-offset-reset=earliest`로 미소비 메시지 처리 — 유실 0
- [ ] DB UK 위반(재소비) 시 Consumer가 ack (재시도 없음)
- [ ] DLT 메시지는 `@DltHandler`에서 ERROR 로그로 수신 확인
- [ ] 기존 동시성 테스트가 Kafka Consumer 대기 로직 추가 후 통과 (초과 발급 0건)

---

## 11. Next Steps

1. [ ] 구현 (`/pdca do 04-kafka-consumer`)
2. [ ] 동시성 테스트 회귀 — CountDownLatch 기반 Consumer 대기 추가
3. [ ] Gap 분석 (`/pdca analyze 04-kafka-consumer`)
4. [ ] 다음 feature: `05-waiting-queue` — Kafka 앞단에 대기열 추가

---

## Version History

| Version | Date | Changes | Author |
|---------|------|---------|--------|
| 0.1 | 2026-04-20 | Initial design — Producer/Consumer 분리, `@RetryableTopic`, UUID v7 사전 생성, Producer 실패 시 Redis 보상 | beomjin |
