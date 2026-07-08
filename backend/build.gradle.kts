import org.jetbrains.kotlin.gradle.tasks.KotlinCompile

plugins {
    kotlin("jvm") version "2.4.0"
    kotlin("plugin.spring") version "2.4.0"
    kotlin("plugin.jpa") version "2.4.0"
    id("org.springframework.boot") version "4.1.0"
    id("io.spring.dependency-management") version "1.1.7"
}

group = "com.sephilabs.tradelog"
version = "0.1.0"

java {
    toolchain {
        languageVersion = JavaLanguageVersion.of(25)
    }
}

repositories {
    mavenCentral()
}

dependencies {
    // Spring Boot core
    implementation("org.springframework.boot:spring-boot-starter-web")
    implementation("org.springframework.boot:spring-boot-starter-validation")
    implementation("org.springframework.boot:spring-boot-starter-data-jpa")
    implementation("org.springframework.boot:spring-boot-starter-security")
    implementation("org.springframework.boot:spring-boot-starter-actuator")
    implementation("org.springframework.session:spring-session-jdbc")

    // Kotlin
    implementation("org.jetbrains.kotlin:kotlin-reflect")
    // Jackson 3 Kotlin module — Spring Boot 4's HTTP mapper is Jackson 3 (tools.jackson); without
    // this, request bodies ignore Kotlin defaults/nullability and a missing optional field 500s.
    implementation("tools.jackson.module:jackson-module-kotlin")
    // Jackson 2 modules for the internal com.fasterxml ObjectMapper (sync cursor / credentials JSON).
    implementation("com.fasterxml.jackson.module:jackson-module-kotlin")
    implementation("com.fasterxml.jackson.datatype:jackson-datatype-jsr310")

    // CSV export for positions
    implementation("com.fasterxml.jackson.dataformat:jackson-dataformat-csv")

    // Quantfury PDF "Trading History Report" parsing
    implementation("org.apache.pdfbox:pdfbox:3.0.3")

    // Persistence
    runtimeOnly("org.postgresql:postgresql")
    implementation("org.springframework.boot:spring-boot-flyway")
    implementation("org.flywaydb:flyway-database-postgresql")

    // Observability
    implementation("io.micrometer:micrometer-registry-prometheus")

    // Rate limiting (per-exchange sync throttling, login + import limits)
    implementation("com.bucket4j:bucket4j_jdk17-core:8.19.0")

    // Argon2 password hashing (Spring Security delegates to BouncyCastle)
    implementation("org.bouncycastle:bcprov-jdk18on:1.84")

    // Tests
    testImplementation("org.springframework.boot:spring-boot-starter-test")
    testImplementation("org.springframework.boot:spring-boot-testcontainers")
    testImplementation("org.springframework.security:spring-security-test")
    testImplementation("org.testcontainers:junit-jupiter")
    testImplementation("org.testcontainers:postgresql")
}

dependencyManagement {
    imports {
        mavenBom("org.testcontainers:testcontainers-bom:1.21.4")
    }
}

tasks.withType<KotlinCompile> {
    compilerOptions {
        freeCompilerArgs.addAll("-Xjsr305=strict")
        jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_25)
    }
}

tasks.withType<Test> {
    useJUnitPlatform()
    testLogging {
        events("passed", "skipped", "failed")
    }
}
