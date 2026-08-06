// SPDX-License-Identifier: AGPL-3.0-only
package com.sephilabs.tradelog.datasource

import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test

/** Invariants for every kind: names fit their columns, and no two venues share a label. */
class SourceKindTest {

    /** `data_sources.kind` and `positions.source` are both VARCHAR(16). */
    private val kindColumnWidth = 16

    /** `positions.exchange` and `capital_snapshots.exchange` are VARCHAR(64). */
    private val venueColumnWidth = 64

    @Test
    fun `every kind name fits the column that stores it`() {
        SourceKind.entries.forEach {
            assertThat(it.name.length)
                .describedAs("%s is %d chars; the kind column holds %d", it, it.name.length, kindColumnWidth)
                .isLessThanOrEqualTo(kindColumnWidth)
        }
    }

    @Test
    fun `every venue label fits the column that stores it`() {
        SourceKind.entries.mapNotNull { it.venueLabel }.forEach {
            assertThat(it.length).describedAs(it).isLessThanOrEqualTo(venueColumnWidth)
        }
    }

    @Test
    fun `each exchange has its own venue label, so no two share a balance`() {
        val labels = SourceKind.entries.mapNotNull { it.venueLabel }
        assertThat(labels).doesNotHaveDuplicates()
        // Venues.canonical compares case- and punctuation-insensitively, so the comparison forms must
        // be distinct too — "Gate.io" and "gateio" would otherwise be one venue.
        assertThat(labels.map { l -> l.lowercase().filter { it.isLetterOrDigit() } }).doesNotHaveDuplicates()
    }

    @Test
    fun `only the file-import sources lack a venue and an API`() {
        val fileSources = setOf(SourceKind.QUANTFURY, SourceKind.JOURNAL_CSV)
        SourceKind.entries.forEach {
            assertThat(it.isApi).describedAs("%s.isApi", it).isEqualTo(it !in fileSources)
        }
        // Quantfury is a venue in its own right; a Journal CSV names its venue per row instead.
        assertThat(SourceKind.JOURNAL_CSV.venueLabel).isNull()
        assertThat(SourceKind.entries.filter { it.venueLabel == null }).containsExactly(SourceKind.JOURNAL_CSV)
    }

    @Test
    fun `exactly the three passphrase exchanges require one`() {
        val expected =
            setOf(SourceKind.OKX, SourceKind.BITGET, SourceKind.BITGET_CLASSIC, SourceKind.KUCOIN_FUTURES)
        assertThat(SourceKind.entries.filter { it.requiresPassphrase }).containsExactlyInAnyOrderElementsOf(expected)
        // A file-import source can never need API credentials, let alone a passphrase.
        assertThat(SourceKind.entries.filter { it.requiresPassphrase }).allMatch { it.isApi }
    }

    @Test
    fun `an exchange's two API generations stay distinct venues`() {
        // Bitget classic and UTA keys are not interchangeable, and here they are also kept as separate
        // venues — so their capital and ROI are tracked apart rather than as one balance.
        assertThat(SourceKind.BITGET.venueLabel).isEqualTo("Bitget")
        assertThat(SourceKind.BITGET_CLASSIC.venueLabel).isEqualTo("Bitget Classic")
        assertThat(Venues.canonical("bitget classic")).isEqualTo("Bitget Classic")
        assertThat(Venues.canonical("bitget")).isEqualTo("Bitget")
    }

    @Test
    fun `Kraken spot and Kraken Futures are two venues, not one`() {
        // Separate platforms with separate accounts and balances; a spot history must never fold into
        // the futures one.
        assertThat(SourceKind.KRAKEN_SPOT.venueLabel).isEqualTo("Kraken")
        assertThat(SourceKind.KRAKEN_FUTURES.venueLabel).isEqualTo("Kraken Futures")
        assertThat(Venues.canonical("kraken")).isEqualTo("Kraken")
        assertThat(Venues.canonical("KRAKEN SPOT")).isEqualTo("KRAKEN SPOT")
    }

    @Test
    fun `Kraken Futures is kept distinct from a plain Kraken venue`() {
        // Kraken's futures platform is a separate account with its own balance; if a Kraken spot
        // history is imported by CSV as "Kraken", the two must not fold into one venue.
        assertThat(SourceKind.KRAKEN_FUTURES.venueLabel).isEqualTo("Kraken Futures")
        assertThat(Venues.canonical("Kraken")).isEqualTo("Kraken")
        assertThat(Venues.canonical("kraken futures")).isEqualTo("Kraken Futures")
    }
}
