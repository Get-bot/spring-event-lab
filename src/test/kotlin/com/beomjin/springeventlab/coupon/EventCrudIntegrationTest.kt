package com.beomjin.springeventlab.coupon

import com.beomjin.springeventlab.support.EventFixture
import com.jayway.jsonpath.JsonPath
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.boot.testcontainers.service.connection.ServiceConnection
import org.springframework.http.MediaType
import org.springframework.test.context.ActiveProfiles
import org.springframework.test.web.servlet.MockMvc
import org.springframework.test.web.servlet.get
import org.springframework.test.web.servlet.post
import org.testcontainers.containers.GenericContainer
import org.testcontainers.kafka.KafkaContainer
import org.testcontainers.postgresql.PostgreSQLContainer
import org.testcontainers.utility.DockerImageName
import java.util.UUID

@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
class EventCrudIntegrationTest(
    private val mockMvc: MockMvc,
) : FunSpec({

    // === Happy Path (FR-T15) ===

    test("POST 생성 → GET 상세로 동일한 데이터를 조회한다") {
        val createResult =
            mockMvc
                .post("/api/v1/events") {
                    contentType = MediaType.APPLICATION_JSON
                    content = EventFixture.createRequestJson()
                }.andExpect {
                    status { isCreated() }
                }.andReturn()

        val id = JsonPath.read<String>(createResult.response.contentAsString, "$.id")

        mockMvc
            .get("/api/v1/events/$id")
            .andExpect {
                status { isOk() }
                jsonPath("$.title") { value("여름 쿠폰 이벤트") }
                jsonPath("$.totalQuantity") { value(100) }
                jsonPath("$.eventStatus") { value("READY") }
            }
    }

    // === Search (FR-T15) ===

    test("여러 이벤트 생성 후 keyword 필터 검색이 정상 동작한다") {
        listOf("AAA 이벤트", "BBB 이벤트", "AAA 특별").forEach { title ->
            mockMvc
                .post("/api/v1/events") {
                    contentType = MediaType.APPLICATION_JSON
                    content = EventFixture.createRequestJson(title = title)
                }.andExpect { status { isCreated() } }
        }

        val result =
            mockMvc
                .get("/api/v1/events?keyword=AAA")
                .andExpect {
                    status { isOk() }
                }.andReturn()

        val count = JsonPath.read<Int>(result.response.contentAsString, "$.content.length()")
        count shouldBe 2
    }

    // === Validation (FR-T16) ===

    test("POST startedAt >= endedAt이면 400 INVALID_DATE_RANGE") {
        mockMvc
            .post("/api/v1/events") {
                contentType = MediaType.APPLICATION_JSON
                content =
                    EventFixture.createRequestJson(
                        startedAt = "2026-07-07T23:59:59Z",
                        endedAt = "2026-07-01T00:00:00Z",
                    )
            }.andExpect {
                status { isBadRequest() }
                jsonPath("$.code") { value("C400-1") }
            }
    }

    // === Not Found (FR-T17) ===

    test("존재하지 않는 UUID로 GET 상세 시 404 EVENT_NOT_FOUND") {
        mockMvc
            .get("/api/v1/events/${UUID.randomUUID()}")
            .andExpect {
                status { isNotFound() }
                jsonPath("$.code") { value("E404") }
            }
    }
}) {
    companion object {
        @ServiceConnection
        @JvmStatic
        val postgres = PostgreSQLContainer(DockerImageName.parse("postgres:18-alpine")).apply { start() }

        @ServiceConnection(name = "redis")
        @JvmStatic
        val redis: GenericContainer<*> =
            GenericContainer(DockerImageName.parse("redis:7-alpine"))
                .withExposedPorts(6379)
                .apply { start() }

        @ServiceConnection
        @JvmStatic
        val kafka = KafkaContainer(DockerImageName.parse("apache/kafka-native:latest")).apply { start() }
    }
}
