package com.beomjin.springeventlab.global.config

import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import org.springframework.core.io.ClassPathResource
import org.springframework.data.redis.core.script.RedisScript

@Configuration
class RedisConfig {
    @Bean
    fun issueCouponScript(): RedisScript<Long> = RedisScript.of(ClassPathResource("scripts/issue_coupon.lua"), Long::class.java)
}
