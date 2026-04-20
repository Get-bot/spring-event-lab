package com.beomjin.springeventlab.coupon.producer

import com.beomjin.springeventlab.coupon.dto.message.CouponIssueMessage
import com.beomjin.springeventlab.coupon.repository.RedisStockRepository
import com.beomjin.springeventlab.global.config.KafkaConfig
import com.beomjin.springeventlab.global.exception.BusinessException
import com.beomjin.springeventlab.global.exception.ErrorCode
import io.github.oshai.kotlinlogging.KotlinLogging
import org.springframework.kafka.core.KafkaTemplate
import org.springframework.stereotype.Component
import java.util.concurrent.TimeUnit

private val log = KotlinLogging.logger {}

@Component
class CouponIssueProducer(
    private val kafkaTemplate: KafkaTemplate<String, CouponIssueMessage>,
    private val redisStockRepository: RedisStockRepository,
) {
    fun publish(message: CouponIssueMessage) {
        try {
            kafkaTemplate
                .send(KafkaConfig.COUPON_ISSUE_TOPIC, message.eventId.toString(), message)
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
