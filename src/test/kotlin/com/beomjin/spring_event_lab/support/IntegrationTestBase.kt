package com.beomjin.springeventlab.support

import org.springframework.boot.test.context.SpringBootTest
import org.springframework.test.context.ActiveProfiles
import org.springframework.test.context.DynamicPropertyRegistry
import org.springframework.test.context.DynamicPropertySource
import org.testcontainers.containers.GenericContainer
import org.testcontainers.containers.PostgreSQLContainer
import org.testcontainers.junit.jupiter.Container
import org.testcontainers.junit.jupiter.Testcontainers
import org.testcontainers.kafka.KafkaContainer
import org.testcontainers.utility.DockerImageName

@SpringBootTest
@ActiveProfiles("test")
@Testcontainers
abstract class IntegrationTestBase {

    companion object {
        @Container
        @JvmField
        val postgres: PostgreSQLContainer<*> =
            PostgreSQLContainer(DockerImageName.parse("postgres:18-alpine"))

        @Container
        @JvmField
        val redis: GenericContainer<*> =
            GenericContainer(DockerImageName.parse("redis:7-alpine"))
                .withExposedPorts(6379)

        @Container
        @JvmField
        val kafka: KafkaContainer =
            KafkaContainer(DockerImageName.parse("apache/kafka-native:latest"))

        @JvmStatic
        @DynamicPropertySource
        fun configureProperties(registry: DynamicPropertyRegistry) {
            // PostgreSQL
            registry.add("spring.datasource.url") { postgres.jdbcUrl }
            registry.add("spring.datasource.username") { postgres.username }
            registry.add("spring.datasource.password") { postgres.password }
            // Redis
            registry.add("spring.data.redis.host") { redis.host }
            registry.add("spring.data.redis.port") { redis.getMappedPort(6379) }
            // Kafka
            registry.add("spring.kafka.bootstrap-servers") { kafka.bootstrapServers }
        }
    }
}
