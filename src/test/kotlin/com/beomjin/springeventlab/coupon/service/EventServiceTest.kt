package com.beomjin.springeventlab.coupon.service

import com.beomjin.springeventlab.coupon.dto.request.EventSearchCond
import com.beomjin.springeventlab.coupon.entity.Event
import com.beomjin.springeventlab.coupon.entity.EventStatus
import com.beomjin.springeventlab.coupon.repository.EventQueryRepository
import com.beomjin.springeventlab.coupon.repository.EventRepository
import com.beomjin.springeventlab.global.exception.BusinessException
import com.beomjin.springeventlab.global.exception.ErrorCode
import com.beomjin.springeventlab.support.EventFixture
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.collections.shouldHaveSize
import io.kotest.matchers.shouldBe
import io.mockk.clearAllMocks
import io.mockk.every
import io.mockk.mockk
import io.mockk.slot
import io.mockk.verify
import org.springframework.data.domain.Page
import org.springframework.data.domain.PageImpl
import org.springframework.data.domain.PageRequest
import org.springframework.data.domain.Pageable
import org.springframework.data.repository.findByIdOrNull
import java.util.UUID

class EventServiceTest :
    FunSpec({

        val eventRepository = mockk<EventRepository>()
        val eventQueryRepository = mockk<EventQueryRepository>()
        val service = EventService(eventRepository, eventQueryRepository)

        beforeTest { clearAllMocks() }

        // === create ===

        test("create는 request를 Entity로 변환해 저장하고 EventResponse를 반환한다") {
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

        test("create는 request의 필드를 Entity에 정확히 매핑하여 save에 전달한다") {
            // given
            val request = EventFixture.createRequest(title = "슬롯 검증", totalQuantity = 77)
            val entitySlot = slot<Event>()
            every { eventRepository.save(capture(entitySlot)) } answers { entitySlot.captured }

            // when
            service.create(request)

            // then
            entitySlot.captured.title shouldBe "슬롯 검증"
            entitySlot.captured.totalQuantity shouldBe 77
            entitySlot.captured.eventStatus shouldBe EventStatus.READY
        }

        // === getEvent ===

        test("getEvent는 존재하는 ID에 대해 EventResponse를 반환한다") {
            // given
            val event = EventFixture.event()
            every { eventRepository.findByIdOrNull(event.id) } returns event

            // when
            val response = service.getEvent(event.id)

            // then
            response.id shouldBe event.id
            response.title shouldBe event.title
        }

        test("getEvent는 존재하지 않는 ID에 대해 EVENT_NOT_FOUND를 던진다") {
            // given
            val id = UUID.randomUUID()
            every { eventRepository.findByIdOrNull(id) } returns null

            // when/then
            shouldThrow<BusinessException> { service.getEvent(id) }
                .errorCode shouldBe ErrorCode.EVENT_NOT_FOUND
        }

        // === getEvents ===

        test("getEvents는 eventQueryRepository.search에 조건과 Pageable을 위임한다") {
            // given
            val cond = EventSearchCond(keyword = "여름")
            val pageable = PageRequest.of(0, 20)
            val events = listOf(EventFixture.event())
            val page: Page<Event> = PageImpl(events, pageable, 1)

            val condSlot = slot<EventSearchCond>()
            val pageableSlot = slot<Pageable>()
            every { eventQueryRepository.search(capture(condSlot), capture(pageableSlot)) } returns page

            // when
            val result = service.getEvents(cond, pageable)

            // then
            condSlot.captured.keyword shouldBe "여름"
            pageableSlot.captured.pageSize shouldBe 20
            result.content shouldHaveSize 1
            result.totalElements shouldBe 1
        }

        test("getEvents는 빈 결과에 대해 빈 PageResponse를 반환한다") {
            // given
            val cond = EventSearchCond()
            val pageable = PageRequest.of(0, 20)
            every { eventQueryRepository.search(any(), any()) } returns Page.empty()

            // when
            val result = service.getEvents(cond, pageable)

            // then
            result.content shouldHaveSize 0
            result.totalElements shouldBe 0
        }
    })
