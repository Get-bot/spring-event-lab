package com.beomjin.springeventlab

import org.springframework.boot.fromApplication
import org.springframework.boot.with


fun main(args: Array<String>) {
    fromApplication<SpringEventLabApplication>().with(TestcontainersConfiguration::class).run(*args)
}
