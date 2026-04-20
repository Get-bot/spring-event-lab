package com.beomjin.springeventlab.global.config

import com.beomjin.springeventlab.coupon.dto.message.CouponIssueMessage
import org.apache.kafka.clients.admin.NewTopic
import org.apache.kafka.clients.consumer.ConsumerConfig
import org.apache.kafka.clients.producer.ProducerConfig
import org.apache.kafka.common.serialization.StringDeserializer
import org.apache.kafka.common.serialization.StringSerializer
import org.springframework.boot.kafka.autoconfigure.KafkaProperties
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import org.springframework.kafka.config.ConcurrentKafkaListenerContainerFactory
import org.springframework.kafka.config.TopicBuilder
import org.springframework.kafka.core.ConsumerFactory
import org.springframework.kafka.core.DefaultKafkaConsumerFactory
import org.springframework.kafka.core.DefaultKafkaProducerFactory
import org.springframework.kafka.core.KafkaTemplate
import org.springframework.kafka.core.ProducerFactory
import org.springframework.kafka.listener.ContainerProperties
import org.springframework.kafka.support.serializer.JacksonJsonDeserializer
import org.springframework.kafka.support.serializer.JacksonJsonSerializer

@Configuration
class KafkaConfig {

    @Bean
    fun producerFactory(props: KafkaProperties): ProducerFactory<String, CouponIssueMessage> {
        val config = props.buildProducerProperties().toMutableMap()
        config[ProducerConfig.KEY_SERIALIZER_CLASS_CONFIG] = StringSerializer::class.java
        config[ProducerConfig.VALUE_SERIALIZER_CLASS_CONFIG] = JacksonJsonSerializer::class.java
        return DefaultKafkaProducerFactory(config)
    }

    @Bean
    fun kafkaTemplate(
        producerFactory: ProducerFactory<String, CouponIssueMessage>,
    ): KafkaTemplate<String, CouponIssueMessage> = KafkaTemplate(producerFactory)

    @Bean
    fun consumerFactory(props: KafkaProperties): ConsumerFactory<String, CouponIssueMessage> {
        val config = props.buildConsumerProperties().toMutableMap()
        config[ConsumerConfig.KEY_DESERIALIZER_CLASS_CONFIG] = StringDeserializer::class.java
        config[ConsumerConfig.VALUE_DESERIALIZER_CLASS_CONFIG] = JacksonJsonDeserializer::class.java
        config[JacksonJsonDeserializer.TRUSTED_PACKAGES] = "com.beomjin.springeventlab.*"
        config[JacksonJsonDeserializer.VALUE_DEFAULT_TYPE] = CouponIssueMessage::class.java.name
        return DefaultKafkaConsumerFactory(config)
    }

    @Bean
    fun kafkaListenerContainerFactory(
        consumerFactory: ConsumerFactory<String, CouponIssueMessage>,
    ): ConcurrentKafkaListenerContainerFactory<String, CouponIssueMessage> {
        val factory = ConcurrentKafkaListenerContainerFactory<String, CouponIssueMessage>()
        factory.setConsumerFactory(consumerFactory)
        factory.containerProperties.ackMode = ContainerProperties.AckMode.RECORD
        return factory
    }

    @Bean
    fun couponIssueTopic(): NewTopic =
        TopicBuilder.name(COUPON_ISSUE_TOPIC).partitions(3).replicas(1).build()

    companion object {
        const val COUPON_ISSUE_TOPIC = "coupon-issue"
        const val COUPON_ISSUE_GROUP = "spring-event-lab"
    }
}
