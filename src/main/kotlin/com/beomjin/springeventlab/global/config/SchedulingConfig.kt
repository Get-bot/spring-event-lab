package com.beomjin.springeventlab.global.config

import org.springframework.boot.context.properties.EnableConfigurationProperties
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import org.springframework.scheduling.annotation.EnableScheduling
import org.springframework.scheduling.concurrent.ThreadPoolTaskScheduler

@Configuration
@EnableScheduling
@EnableConfigurationProperties(WaitingQueueProperties::class)
class SchedulingConfig {
    /**
     * `@Scheduled` 메서드 실행용 풀.
     *
     * - poolSize=2: drainQueues 1개 + 향후 추가 스케줄러 여유
     * - waitForTasksToCompleteOnShutdown=true: graceful shutdown 시 진행 중 batch 완료 보장
     *   (ZPOPMIN으로 꺼냈으나 result 기록 전에 종료되면 데이터 손실)
     */
    @Bean
    fun taskScheduler(): ThreadPoolTaskScheduler =
        ThreadPoolTaskScheduler().apply {
            poolSize = 2
            setThreadNamePrefix("scheduler-")
            setWaitForTasksToCompleteOnShutdown(true)
            setAwaitTerminationSeconds(10)
            initialize()
        }
}
