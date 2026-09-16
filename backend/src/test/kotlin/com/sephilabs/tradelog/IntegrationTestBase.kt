// SPDX-License-Identifier: AGPL-3.0-only
package com.sephilabs.tradelog

import com.sephilabs.tradelog.mail.MailTestDoublesConfig
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.context.annotation.Import
import org.springframework.boot.testcontainers.service.connection.ServiceConnection
import org.springframework.test.context.ActiveProfiles
import org.testcontainers.containers.PostgreSQLContainer

/**
 * Base for integration tests: boots the full Spring context against a real PostgreSQL (Testcontainers)
 * with Flyway-managed schema. One container is shared across the test JVM.
 */
@SpringBootTest
@ActiveProfiles("test")
@Import(MailTestDoublesConfig::class)
abstract class IntegrationTestBase {

    companion object {
        @JvmStatic
        @ServiceConnection
        val postgres: PostgreSQLContainer<*> = PostgreSQLContainer("postgres:16-alpine")
            .withDatabaseName("tradelog_test")
            .withUsername("test")
            .withPassword("test")
            .also { it.start() }
    }
}
