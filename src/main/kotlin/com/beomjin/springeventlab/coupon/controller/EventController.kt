package com.beomjin.springeventlab.coupon.controller

import com.beomjin.springeventlab.coupon.dto.request.EventCreateRequest
import com.beomjin.springeventlab.coupon.dto.request.EventSearchCond
import com.beomjin.springeventlab.coupon.dto.response.EventResponse
import com.beomjin.springeventlab.coupon.service.EventService
import com.beomjin.springeventlab.global.common.PageResponse
import io.swagger.v3.oas.annotations.Operation
import io.swagger.v3.oas.annotations.tags.Tag
import jakarta.validation.Valid
import org.springdoc.core.annotations.ParameterObject
import org.springframework.data.domain.Pageable
import org.springframework.data.domain.Sort
import org.springframework.data.web.PageableDefault
import org.springframework.http.HttpStatus
import org.springframework.http.ResponseEntity
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RestController
import java.util.UUID

@RestController
@RequestMapping("/api/v1/events")
@Tag(name = "Event", description = "이벤트 관리 API")
class EventController(
    private val eventService: EventService,
) {
    @PostMapping
    @Operation(summary = "이벤트 생성", description = "새로운 선착순 이벤트를 생성합니다")
    fun create(
        @Valid @RequestBody request: EventCreateRequest,
    ): ResponseEntity<EventResponse> {
        val response = eventService.create(request)
        return ResponseEntity.status(HttpStatus.CREATED).body(response)
    }

    @GetMapping
    @Operation(summary = "이벤트 목록 조회", description = "검색 조건에 따라 이벤트 목록을 페이징하여 조회합니다")
    fun getEvents(
        @ParameterObject @Valid cond: EventSearchCond,
        @ParameterObject
        @PageableDefault(size = 20, sort = ["createdAt"], direction = Sort.Direction.DESC)
        pageable: Pageable,
    ): ResponseEntity<PageResponse<EventResponse>> {
        val response = eventService.getEvents(cond, pageable)
        return ResponseEntity.ok(response)
    }

    @GetMapping("/{id}")
    @Operation(summary = "이벤트 상세 조회", description = "이벤트 ID로 상세 정보를 조회합니다")
    fun getEvent(
        @PathVariable id: UUID,
    ): ResponseEntity<EventResponse> {
        val response = eventService.getEvent(id)
        return ResponseEntity.ok(response)
    }
}
