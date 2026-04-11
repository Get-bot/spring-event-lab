package com.beomjin.springeventlab.global.common

import org.springframework.data.domain.Page

data class PageResponse<T>(
    val content: List<T>,
    val page: Int,
    val size: Int,
    val totalElements: Long,
    val totalPages: Int,
) {
    companion object {
        fun <T : Any> from(page: Page<T>) =
            PageResponse(
                content = page.content,
                page = page.number + 1,
                size = page.size,
                totalElements = page.totalElements,
                totalPages = page.totalPages,
            )
    }
}
