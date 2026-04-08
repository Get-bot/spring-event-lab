package com.beomjin.springeventlab

import org.springframework.boot.autoconfigure.SpringBootApplication
import org.springframework.boot.context.properties.ConfigurationPropertiesScan
import org.springframework.boot.runApplication

@ConfigurationPropertiesScan
@SpringBootApplication
class SpringEventLabApplication

fun main(args: Array<String>) {
    runApplication<SpringEventLabApplication>(*args)
}
