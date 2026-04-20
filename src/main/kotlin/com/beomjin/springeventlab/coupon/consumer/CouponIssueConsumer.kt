package com.beomjin.springeventlab.coupon.consumer

import com.beomjin.springeventlab.coupon.dto.message.CouponIssueMessage
import com.beomjin.springeventlab.coupon.entity.CouponIssue
import com.beomjin.springeventlab.coupon.repository.CouponIssueRepository
import com.beomjin.springeventlab.global.config.KafkaConfig
import io.github.oshai.kotlinlogging.KotlinLogging
import org.springframework.dao.DataIntegrityViolationException
import org.springframework.kafka.annotation.BackOff
import org.springframework.kafka.annotation.DltHandler
import org.springframework.kafka.annotation.KafkaListener
import org.springframework.kafka.annotation.RetryableTopic
import org.springframework.kafka.retrytopic.TopicSuffixingStrategy
import org.springframework.kafka.support.KafkaHeaders
import org.springframework.messaging.handler.annotation.Header
import org.springframework.messaging.handler.annotation.Payload
import org.springframework.stereotype.Component
import org.springframework.transaction.annotation.Transactional

private val log = KotlinLogging.logger {}

@Component
class CouponIssueConsumer(
    private val couponIssueRepository: CouponIssueRepository,
) {
    @RetryableTopic(
        attempts = "4",
        backOff = BackOff(delay = 1000, multiplier = 2.0),
        dltTopicSuffix = ".DLT",
        topicSuffixingStrategy = TopicSuffixingStrategy.SUFFIX_WITH_INDEX_VALUE,
        exclude = [DataIntegrityViolationException::class],
        autoCreateTopics = "true",
    )
    @KafkaListener(topics = [KafkaConfig.COUPON_ISSUE_TOPIC], groupId = KafkaConfig.COUPON_ISSUE_GROUP)
    @Transactional
    fun consume(message: CouponIssueMessage) {
        try {
            couponIssueRepository.save(
                CouponIssue(id = message.id, eventId = message.eventId, userId = message.userId),
            )
        } catch (e: DataIntegrityViolationException) {
            log.info { "중복 메시지 소비, 무시 — id=${message.id} (${e.javaClass.simpleName})" }
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
    }
}
