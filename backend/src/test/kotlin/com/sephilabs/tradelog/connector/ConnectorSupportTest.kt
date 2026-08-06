// SPDX-License-Identifier: AGPL-3.0-only
package com.sephilabs.tradelog.connector

import com.fasterxml.jackson.databind.ObjectMapper
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import java.math.BigDecimal
import java.time.Duration

/** The small shared pieces the connectors lean on: skip accounting, contract sizes, memoisation. */
class ConnectorSupportTest {

    private val mapper = ObjectMapper()
    private fun node(json: String) = mapper.readTree(json)

    @Test
    fun `a tally keeps mapped rows and counts the rest by reason`() {
        val tally = SkipTally()
        val kept = mutableListOf<String>()

        assertThat(tally.keep(node("{}"), Mapped.Ok("a"), kept)).isTrue
        assertThat(tally.keep(node("""{ "bad": 1 }"""), Mapped.Skip("no symbol"), kept)).isFalse
        tally.keep(node("""{ "bad": 2 }"""), Mapped.Skip("no symbol"), kept)
        tally.keep(node("""{ "bad": 3 }"""), Mapped.Skip("no price"), kept)

        assertThat(kept).containsExactly("a")
        assertThat(tally.total).isEqualTo(3)
    }

    @Test
    fun `a tally with nothing skipped stays silent`() {
        val tally = SkipTally()
        tally.keep(node("{}"), Mapped.Ok(1), mutableListOf())
        assertThat(tally.total).isZero
    }

    @Test
    fun `contract sizes are read from a public listing and are case-insensitive`() {
        val listing = node(
            """
            { "data": { "symbols": [
              { "symbol": "BTCUSDT", "contract_size": "0.001" },
              { "symbol": "ETHUSDT", "contractSize": "0.01" }
            ] } }
            """.trimIndent(),
        )
        val sizes = ContractSizes.from(
            listing,
            rowPaths = listOf("data.symbols"),
            symbolKeys = listOf("symbol"),
            sizeKeys = listOf("contract_size", "contractSize"),
        )
        assertThat(sizes.size).isEqualTo(2)
        assertThat(sizes.of("BTCUSDT")).isEqualByComparingTo("0.001")
        assertThat(sizes.of("btcusdt")).isEqualByComparingTo("0.001")
        assertThat(sizes.of("ETHUSDT")).isEqualByComparingTo("0.01")
    }

    @Test
    fun `an unknown or unusable size falls back to one, never to zero`() {
        // Multiplying a quantity by zero would report every trade as size 0; 1 leaves the venue's own
        // units in place, which the reconstructor pairs identically either way.
        val listing = node(
            """
            { "data": { "symbols": [
              { "symbol": "ZEROUSDT", "contract_size": "0" },
              { "symbol": "NEGUSDT", "contract_size": "-1" },
              { "contract_size": "5" },
              { "symbol": "NOSIZEUSDT" }
            ] } }
            """.trimIndent(),
        )
        val sizes = ContractSizes.from(listing, listOf("data.symbols"), listOf("symbol"), listOf("contract_size"))
        assertThat(sizes.size).isZero
        assertThat(sizes.of("ZEROUSDT")).isEqualByComparingTo(BigDecimal.ONE)
        assertThat(sizes.of("NEGUSDT")).isEqualByComparingTo(BigDecimal.ONE)
        assertThat(sizes.of("NOSIZEUSDT")).isEqualByComparingTo(BigDecimal.ONE)
        assertThat(sizes.of("ANYTHING")).isEqualByComparingTo(BigDecimal.ONE)
    }

    @Test
    fun `a failed public read degrades to no sizes rather than failing the sync`() {
        val sizes = ContractSizes.from(null, listOf("data"), listOf("symbol"), listOf("size"))
        assertThat(sizes.size).isZero
        assertThat(sizes.of("BTCUSDT")).isEqualByComparingTo(BigDecimal.ONE)
    }

    @Test
    fun `a memo loads once and then serves the cached value`() {
        var loads = 0
        val memo = Memo(Duration.ofHours(1)) { loads++; "v$loads" }

        assertThat(memo.get()).isEqualTo("v1")
        assertThat(memo.get()).isEqualTo("v1")
        assertThat(memo.get()).isEqualTo("v1")
        assertThat(loads).isEqualTo(1)
    }

    @Test
    fun `an expired memo reloads`() {
        var loads = 0
        // A zero TTL is always expired, which is the boundary of the staleness check.
        val memo = Memo(Duration.ZERO) { loads++; loads }

        memo.get()
        memo.get()
        assertThat(loads).isEqualTo(2)
    }

    @Test
    fun `a memo whose load fails does not cache the failure`() {
        var attempts = 0
        val memo = Memo(Duration.ofHours(1)) {
            attempts++
            if (attempts < 3) throw IllegalStateException("venue down") else "ok"
        }

        repeat(2) { runCatching { memo.get() } }
        assertThat(memo.get()).isEqualTo("ok")
        assertThat(attempts).isEqualTo(3)
    }
}
