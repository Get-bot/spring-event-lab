plugins {
    id("org.springframework.boot") version "4.0.5"
    id("io.spring.dependency-management") version "1.1.7"
    id("org.jetbrains.kotlinx.kover") version "0.9.8"
    id("com.google.devtools.ksp") version "2.3.6"
    kotlin("jvm") version "2.3.20"
    kotlin("plugin.spring") version "2.3.20"
    kotlin("plugin.jpa") version "2.3.20"
}

group = "com.beomjin"
version = "0.0.1-SNAPSHOT"

val queryDslVersion = "7.1"
val springDocVersion = "3.0.2"
val kotlinLoggingVersion = "7.0.3"
val redissonVersion = "4.3.0"

java {
    toolchain {
        languageVersion = JavaLanguageVersion.of(25)
    }
}

repositories {
    mavenCentral()
}

extra["springCloudVersion"] = "2025.1.1"

dependencies {
    // Spring Boot Starters
    implementation("org.springframework.boot:spring-boot-starter-actuator")
    implementation("org.springframework.boot:spring-boot-starter-data-jpa")
    implementation("org.springframework.boot:spring-boot-starter-data-redis")
    implementation("org.springframework.boot:spring-boot-starter-kafka")
    implementation("org.springframework.boot:spring-boot-starter-validation")
    implementation("org.springframework.boot:spring-boot-starter-webmvc")
    implementation("org.jetbrains.kotlin:kotlin-reflect")
    implementation("org.springframework.cloud:spring-cloud-starter-circuitbreaker-resilience4j")
    implementation("tools.jackson.module:jackson-module-kotlin")

    // Flyway
    implementation("org.flywaydb:flyway-core")
    implementation("org.flywaydb:flyway-database-postgresql")

    // QueryDSL (OpenFeign fork, KSP)
    implementation("io.github.openfeign.querydsl:querydsl-jpa:$queryDslVersion")
    implementation("org.springframework.boot:spring-boot-starter-session-data-redis")
    ksp("io.github.openfeign.querydsl:querydsl-ksp-codegen:$queryDslVersion")

    // Kotlin Logging
    implementation("io.github.oshai:kotlin-logging-jvm:$kotlinLoggingVersion")

    // SpringDoc OpenAPI
    implementation("org.springdoc:springdoc-openapi-starter-webmvc-ui:$springDocVersion")

    // Apache HttpClient5 (RestClient)
    implementation("org.apache.httpcomponents.client5:httpclient5")

    // Redisson (distributed lock)
    implementation("org.redisson:redisson-spring-boot-starter:$redissonVersion")

    // Runtime
    developmentOnly("org.springframework.boot:spring-boot-devtools")
    runtimeOnly("org.postgresql:postgresql")

    // Utils
    implementation("com.github.f4b6a3:uuid-creator:6.1.1")

    // Test
    testImplementation("org.springframework.boot:spring-boot-starter-actuator-test")
    testImplementation("org.springframework.boot:spring-boot-starter-data-jpa-test")
    testImplementation("org.springframework.boot:spring-boot-starter-data-redis-test")
    testImplementation("org.springframework.boot:spring-boot-starter-kafka-test")
    testImplementation("org.springframework.boot:spring-boot-starter-validation-test")
    testImplementation("org.springframework.boot:spring-boot-starter-webmvc-test")
    testImplementation("org.springframework.boot:spring-boot-testcontainers")
    testImplementation("org.jetbrains.kotlin:kotlin-test-junit5")
    testImplementation("org.testcontainers:testcontainers-junit-jupiter")
    testImplementation("org.testcontainers:testcontainers-kafka")
    testImplementation("org.testcontainers:testcontainers-postgresql")
    testRuntimeOnly("org.junit.platform:junit-platform-launcher")
    implementation(kotlin("stdlib"))
}

dependencyManagement {
    imports {
        mavenBom("org.springframework.cloud:spring-cloud-dependencies:${property("springCloudVersion")}")
    }
}

kotlin {
    compilerOptions {
        freeCompilerArgs.addAll("-Xjsr305=strict", "-Xannotation-default-target=param-property")
    }

    sourceSets.main {
        kotlin.srcDir("build/generated/ksp/main/kotlin")
    }
}

allOpen {
    annotation("jakarta.persistence.Entity")
    annotation("jakarta.persistence.MappedSuperclass")
    annotation("jakarta.persistence.Embeddable")
}

tasks.withType<Test> {
    useJUnitPlatform()
}
