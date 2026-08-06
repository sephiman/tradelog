// SPDX-License-Identifier: AGPL-3.0-only
package com.sephilabs.tradelog.datasource

import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import java.time.Instant

/** The venue-shutdown boundary: still syncing a minute before, never a minute after. */
class SourceKindRetirementTest {

    private val closes = SourceKind.BITMART_CLOSES_AT

    @Test
    fun `BitMart retires on its announced closing date`() {
        assertThat(closes).isEqualTo(Instant.parse("2026-08-26T00:00:00Z"))
        assertThat(SourceKind.BITMART.retiredAt).isEqualTo(closes)
    }

    @Test
    fun `a live venue is never retired`() {
        SourceKind.entries.filter { it != SourceKind.BITMART }.forEach {
            assertThat(it.retiredAt).describedAs("%s must not be retired", it).isNull()
            assertThat(it.isRetired(closes.plusSeconds(3600))).describedAs("%s", it).isFalse
        }
    }

    @Test
    fun `syncing is still allowed right up to the closing instant`() {
        assertThat(SourceKind.BITMART.isRetired(closes.minusSeconds(60))).isFalse
        assertThat(SourceKind.BITMART.isRetired(Instant.parse("2026-08-25T23:59:59Z"))).isFalse
    }

    @Test
    fun `the closing instant itself retires the venue, and so does anything after it`() {
        assertThat(SourceKind.BITMART.isRetired(closes)).isTrue
        assertThat(SourceKind.BITMART.isRetired(closes.plusSeconds(60))).isTrue
        assertThat(SourceKind.BITMART.isRetired(Instant.parse("2030-01-01T00:00:00Z"))).isTrue
    }

    @Test
    fun `retiring a venue does not stop it being an API source`() {
        // It must keep its credentials, cursor and connector: history stays readable and restorable,
        // and the source is only frozen — never converted into something else or removed.
        assertThat(SourceKind.BITMART.isApi).isTrue
        assertThat(SourceKind.BITMART.venueLabel).isEqualTo("BitMart")
    }
}
