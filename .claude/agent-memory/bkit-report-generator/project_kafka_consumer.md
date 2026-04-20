---
name: Kafka Consumer Feature Completion (04-kafka-consumer)
description: Peak Load Shifting pattern — Redis Producer + Kafka async Consumer with @RetryableTopic, UUID v7 pre-generation, 97% Match Rate, Documentation Debt lessons learned
type: project
---

## Kafka Consumer Feature (04-kafka-consumer) — Flash Sale Roadmap 4/5

**Status**: ✅ COMPLETED — Match Rate 97%, All success criteria passed

### PDCA Timeline
- **Plan**: 2026-04-09 (v0.1) — 5 FR, scope clear
- **Design**: 2026-04-20 (v0.1) — 9 design decisions, 11 error paths
- **Do**: 2026-04-20 — 4 NEW + 6 MODIFY + 1 DELETE, 450 lines added
- **Check**: 2026-04-20 — Match Rate 97% (functional gap 0, documentation debt 3%)

### Why: Architectural Learning

**Problem**: redis-stock synchronously saves to DB after Redis success → HikariCP pool exhaustion at 10K TPS spike

**Solution**: Producer publishes to Kafka + returns 201 immediately with UUID v7 pre-generated id. Consumer processes asynchronously with @RetryableTopic + DLT, achieving Peak Load Shifting pattern (10K TPS input → 500 TPS DB output).

### How to Apply

**For 05-waiting-queue Design** (next feature):
- Use Spring Kafka 4.x API from the start: `JacksonJsonSerializer`, `BackOff` (not `Backoff`), `backOff =` parameter (not `backoff =`)
- Package moved: `org.springframework.kafka.annotation.BackOff` (not `org.springframework.retry.annotation.Backoff`)
- Use `buildProducerProperties()` / `buildConsumerProperties()` (no null argument)
- Verify Design code snippets by Copy → Paste testing in actual project

### Key Decisions Implemented

1. **UUID v7 Pre-generation** — Service generates id → mesg → response (no Consumer wait)
2. **Synchronous Send + Async Process** — `send().get(3s)` then Redis compensate or 201 response
3. **@RetryableTopic Declarative** — 4 attempts, exponential backoff (1s, 2s, 4s), exclude DataIntegrityViolationException
4. **Dual Idempotency** — exclude + try/catch log + DB UK constraint
5. **Partition Key = eventId** — same event messages go to same partition (order guarantee)

### Documentation Debt Analysis

**Match Rate 97%** breakdown:
- Functional Gap: **0%** (9/9 success criteria, 11/11 error paths)
- Documentation Debt: **3%** (5 items related to Spring Kafka API versioning)

| Gap ID | Issue | Design | Implementation | Spring Kafka Version |
|--------|-------|--------|-----------------|----------------------|
| G1 | JsonSerializer class | Mentioned | JacksonJsonSerializer | 4.x standard |
| G2 | JsonDeserializer class | Mentioned | JacksonJsonDeserializer | 4.x standard |
| G3 | backoff field name | `backoff =` | `backOff =` (camelCase) | 4.x with spring-retry split |
| G4 | buildProducerProperties() | null argument | no argument | Spring Boot 4.0.5 |
| G8 | retry topic naming | diagram implied | `SUFFIX_WITH_INDEX_VALUE` explicit | 4.x strategy |

**Meaning**: Code works perfectly; docs need Spring Kafka 4.x update.

### Files Changed

**NEW** (4):
- `CouponIssueMessage.kt` — data class (id, eventId, userId, issuedAt)
- `CouponIssueProducer.kt` — publish + compensate
- `CouponIssueConsumer.kt` — @RetryableTopic + @DltHandler
- `KafkaConfig.kt` — factories + topic bean

**MODIFY** (6):
- `CouponIssue.kt` — id parameter added (default: UuidCreator)
- `CouponIssueService.kt` — TxService → Producer dependency
- `ErrorCode.kt` — +COUPON_PUBLISH_FAILED
- `ErrorCodeMapper.kt` — 503 mapping
- `application.yaml` — Kafka JSON/ack settings
- `CouponIssueConcurrencyTest.kt` — awaitDbCount() helper

**DELETE** (1):
- `CouponIssueWriter.kt` (includes CouponIssueTxService) — DB write moved to Consumer

### Test Regression

**CouponIssueConcurrencyTest** — 4 TC all pass with `awaitDbCount()` Consumer wait:
- TC-01: 3000 requests, 1000 coupons → 1000 issued (0 excess)
- TC-02: 100 requests same userId → 1 issued (0 duplicates)
- TC-03: 1000 requests sold-out → 0 additional issued
- TC-04: Redis issued set count == DB count (consistency verified)

### Lessons Learned

**L1**: Always check framework/library version before writing Design
- Design v0.1 written for Spring Kafka 2.x/3.x API
- Implementation uses 4.0.4 (latest in Spring Boot 4.0.5)
- Result: 3% documentation debt (code words differ, functionality identical)

**L2**: Documentation Debt ≠ Functional Gap
- A 97% match rate here means: functional 100% + API versioning 3%
- Not a code quality issue, but a documentation maintenance issue

**L3**: Consumer idempotency requires 3-layer defense
- Kafka level: `exclude = [DataIntegrityViolationException]`
- Consumer logic: try/catch + log + swallow
- DB level: `uk_coupon_issue(event_id, user_id)` constraint
- Single layer insufficient; all three must work together

**L4**: Test helper `awaitDbCount()` prevents production code pollution
- No changes to CouponIssueService/Consumer needed
- Test-only polling helper: `withTimeoutOrNull + while + delay`
- Reusable pattern for async verification

### Next Steps

1. **Design v0.2 Update** (optional, separate PR):
   - Replace code snippets with Spring Kafka 4.x API
   - All 5 documentation debt items
   - Zero functional impact (implementation already correct)

2. **05-waiting-queue Feature** (next, Flash Sale 5/5):
   - Apply Spring Kafka 4.x API from Design start
   - Verify Design reproducibility (Copy → Paste test)
   - Add Kafka queue + backpressure handling before Consumer

3. **Archive Completed Work**:
   - Move 04-kafka-consumer PDCA docs if needed
   - Record learning in project wiki/knowledge base
