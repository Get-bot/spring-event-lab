package com.beomjin.springeventlab.coupon.controller

import com.beomjin.springeventlab.coupon.dto.request.EventSearchCond
import com.beomjin.springeventlab.coupon.entity.EventStatus
import com.beomjin.springeventlab.coupon.service.EventService
import com.beomjin.springeventlab.global.common.PageResponse
import com.beomjin.springeventlab.global.exception.BusinessException
import com.beomjin.springeventlab.global.exception.ErrorCode
import com.beomjin.springeventlab.support.EventFixture
import com.ninjasquad.springmockk.MockkBean
import io.kotest.core.spec.style.FunSpec
import io.kotest.datatest.withData
import io.kotest.matchers.collections.shouldContainExactlyInAnyOrder
import io.kotest.matchers.shouldBe
import io.mockk.every
import io.mockk.slot
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest
import org.springframework.data.domain.Page
import org.springframework.data.domain.Pageable
import org.springframework.http.MediaType
import org.springframework.test.web.servlet.MockMvc
import org.springframework.test.web.servlet.get
import org.springframework.test.web.servlet.post
import java.util.UUID

@WebMvcTest(EventController::class)
class EventControllerTest(
    private val mockMvc: MockMvc,
    @MockkBean private val eventService: EventService,
) : FunSpec({

    // === POST /api/v1/events ===

    test("POST 정상 요청은 201과 EventResponse를 반환한다") {
        every { eventService.create(any()) } returns EventFixture.response()

        mockMvc
            .post("/api/v1/events") {
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
            BadRequestCase(
                "startedAt 누락",
                """{"title":"test","totalQuantity":10,"endedAt":"2026-07-07T23:59:59Z"}""",
            ),
        ) { (_, payload) ->
            mockMvc
                .post("/api/v1/events") {
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

        mockMvc
            .get("/api/v1/events")
            .andExpect {
                status { isOk() }
                jsonPath("$.content") { isArray() }
            }
    }

    test("GET page=1&size=20은 0-based Pageable(pageNumber=0)로 Service에 전달한다") {
        val pageableSlot = slot<Pageable>()
        every { eventService.getEvents(any(), capture(pageableSlot)) } returns PageResponse.from(Page.empty())

        mockMvc
            .get("/api/v1/events?page=1&size=20")
            .andExpect { status { isOk() } }

        pageableSlot.captured.pageNumber shouldBe 0
        pageableSlot.captured.pageSize shouldBe 20
    }

    test("GET statuses=OPEN&statuses=READY는 List<EventStatus>로 바인딩된다") {
        val condSlot = slot<EventSearchCond>()
        every { eventService.getEvents(capture(condSlot), any()) } returns PageResponse.from(Page.empty())

        mockMvc
            .get("/api/v1/events?statuses=OPEN&statuses=READY")
            .andExpect { status { isOk() } }

        condSlot.captured.statuses shouldContainExactlyInAnyOrder
            listOf(EventStatus.OPEN, EventStatus.READY)
    }

    test("GET keyword=a (2자 미만)이면 400") {
        mockMvc
            .get("/api/v1/events?keyword=a")
            .andExpect { status { isBadRequest() } }
    }

    // === GET /api/v1/events/{id} ===

    test("GET /{id} 정상 요청은 200과 EventResponse를 반환한다") {
        val response = EventFixture.response()
        every { eventService.getEvent(any()) } returns response

        mockMvc
            .get("/api/v1/events/${response.id}")
            .andExpect {
                status { isOk() }
                jsonPath("$.title") { value(response.title) }
            }
    }

    test("GET /{id} 존재하지 않는 ID는 404 EVENT_NOT_FOUND") {
        val id = UUID.randomUUID()
        every { eventService.getEvent(id) } throws BusinessException(ErrorCode.EVENT_NOT_FOUND)

        mockMvc
            .get("/api/v1/events/$id")
            .andExpect {
                status { isNotFound() }
                jsonPath("$.code") { value("E404") }
            }
    }
})
