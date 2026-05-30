// SPDX-License-Identifier: AGPL-3.0-only
package com.sephilabs.tradelog

import org.junit.jupiter.api.Test

/** The Spring context boots, Flyway migrations apply, and all beans wire (incl. connectors, sync, session). */
class ApplicationContextTest : IntegrationTestBase() {
    @Test
    fun `context loads`() {
    }
}
