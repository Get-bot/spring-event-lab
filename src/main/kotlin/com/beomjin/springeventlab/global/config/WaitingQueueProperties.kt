package com.beomjin.springeventlab.global.config

import org.springframework.boot.context.properties.ConfigurationProperties

/**
 * application.yaml의 `waiting-queue.*` 키를 매핑.
 *
 * - [pollIntervalMs] : Scheduler tick 간격 (fixedDelay)
 * - [batchSize] : 한 tick에 ZPOPMIN으로 꺼낼 유저 수
 * - [resultTtlSeconds] : `result:{eventId}:{userId}` 키의 TTL
 */
@ConfigurationProperties(prefix = "waiting-queue")
data class WaitingQueueProperties(
    val pollIntervalMs: Long = 1000,
    val batchSize: Int = 100,
    val resultTtlSeconds: Long = 3600,
)
