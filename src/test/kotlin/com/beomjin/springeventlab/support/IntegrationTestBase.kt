package com.beomjin.springeventlab.support

import org.springframework.boot.test.context.SpringBootTest
import org.springframework.boot.testcontainers.service.connection.ServiceConnection
import org.springframework.test.context.ActiveProfiles
import org.testcontainers.containers.GenericContainer
import org.testcontainers.kafka.KafkaContainer
import org.testcontainers.postgresql.PostgreSQLContainer
import org.testcontainers.utility.DockerImageName

@SpringBootTest
@ActiveProfiles("test")
abstract class IntegrationTestBase {
    companion object {
        // 1. PostgreSQL
        @ServiceConnection
        val postgres =
            PostgreSQLContainer(DockerImageName.parse("postgres:18-alpine")).apply {
                start()
            }

        // 2. Redis (GenericContainer이므로 name="redis" 명시 필수)
        @ServiceConnection(name = "redis")
        val redis: GenericContainer<*> =
            GenericContainer(DockerImageName.parse("redis:7-alpine"))
                .withExposedPorts(6379)
                .apply {
                    start()
                }

        // 3. Kafka
        @ServiceConnection
        val kafka =
            KafkaContainer(DockerImageName.parse("apache/kafka-native:latest")).apply {
                start()
            }
    }
}
