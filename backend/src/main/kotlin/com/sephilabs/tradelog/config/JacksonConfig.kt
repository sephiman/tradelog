// SPDX-License-Identifier: AGPL-3.0-only
package com.sephilabs.tradelog.config

import com.fasterxml.jackson.databind.DeserializationFeature
import com.fasterxml.jackson.databind.ObjectMapper
import com.fasterxml.jackson.databind.SerializationFeature
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule
import com.fasterxml.jackson.module.kotlin.KotlinModule
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration

/**
 * A classic Jackson 2 [ObjectMapper] for internal JSON work — encrypting credentials and the sync
 * cursor, and parsing exchange API responses. Declared explicitly because Spring Boot 4 / Spring 7
 * auto-configure Jackson 3 (`tools.jackson`) for HTTP, so no `com.fasterxml` ObjectMapper bean would
 * otherwise exist. HTTP (de)serialization is unaffected and continues to use the Jackson 3 mapper.
 */
@Configuration
class JacksonConfig {

    @Bean
    fun objectMapper(): ObjectMapper =
        ObjectMapper()
            .registerModule(KotlinModule.Builder().build())
            .registerModule(JavaTimeModule())
            .disable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS)
            .disable(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES)
}
