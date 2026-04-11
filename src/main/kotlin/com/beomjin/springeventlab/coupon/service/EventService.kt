package com.beomjin.springeventlab.coupon.service

import com.beomjin.springeventlab.coupon.dto.request.EventCreateRequest
import com.beomjin.springeventlab.coupon.dto.request.EventSearchCond
import com.beomjin.springeventlab.coupon.dto.response.EventResponse
import com.beomjin.springeventlab.coupon.repository.EventQueryRepository
import com.beomjin.springeventlab.coupon.repository.EventRepository
import com.beomjin.springeventlab.global.common.PageResponse
import com.beomjin.springeventlab.global.exception.BusinessException
import com.beomjin.springeventlab.global.exception.ErrorCode
import org.springframework.data.domain.Pageable
import org.springframework.data.repository.findByIdOrNull
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import java.util.UUID

@Service
@Transactional(readOnly = true)
class EventService(
    private val eventRepository: EventRepository,
    private val eventQueryRepository: EventQueryRepository,
) {
    @Transactional
    fun create(request: EventCreateRequest): EventResponse {
        val event = eventRepository.save(request.toEntity())
        return EventResponse.from(event)
    }

    fun getEvents(
        cond: EventSearchCond,
        pageable: Pageable,
    ): PageResponse<EventResponse> {
        val page = eventQueryRepository.search(cond, pageable)
        return PageResponse.from(page.map(EventResponse::from))
    }

    fun getEvent(id: UUID): EventResponse {
        val event =
            eventRepository.findByIdOrNull(id)
                ?: throw BusinessException(ErrorCode.EVENT_NOT_FOUND)
        return EventResponse.from(event)
    }
}
