// SPDX-License-Identifier: AGPL-3.0-only
package com.sephilabs.tradelog.connector

import com.sephilabs.tradelog.IntegrationTestBase
import com.sephilabs.tradelog.datasource.SourceKind
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired

/** Every kind must resolve to exactly one connector bean of the right shape. */
class ConnectorRegistryIntegrationTest @Autowired constructor(
    private val registry: ConnectorRegistry,
    private val connectors: List<Connector>,
) : IntegrationTestBase() {

    @Test
    fun `every kind has exactly one connector`() {
        assertThat(connectors.map { it.kind }).doesNotHaveDuplicates()
        SourceKind.entries.forEach { kind ->
            assertThat(registry.get(kind).kind).describedAs("connector for %s", kind).isEqualTo(kind)
        }
    }

    @Test
    fun `every API kind resolves as an API connector and every file kind as a file connector`() {
        SourceKind.entries.forEach { kind ->
            if (kind.isApi) {
                assertThat(registry.api(kind)).describedAs("API connector for %s", kind).isNotNull
            } else {
                assertThat(registry.file(kind)).describedAs("file connector for %s", kind).isNotNull
            }
        }
    }

    @Test
    fun `every exchange connector normalizes its own symbol format to canonical BASE-QUOTE`() {
        // One real symbol per venue, in that venue's own notation. A normalizer that silently fell back
        // to (whole, USDT) would put "BTCUSDTSWAP" in the pair column and split one market in two.
        val samples = mapOf(
            SourceKind.BITUNIX to "BTCUSDT",
            SourceKind.BINGX to "BTC-USDT",
            SourceKind.BITMART to "BTCUSDT",
            SourceKind.BINANCE_FUTURES to "BTCUSDT",
            SourceKind.BYBIT to "BTCUSDT",
            SourceKind.OKX to "BTC-USDT-SWAP",
            SourceKind.BITGET to "BTCUSDT",
            SourceKind.GATEIO_FUTURES to "BTC_USDT",
            SourceKind.MEXC_FUTURES to "BTC_USDT",
            SourceKind.KUCOIN_FUTURES to "XBTUSDTM",
        )
        samples.forEach { (kind, raw) ->
            assertThat(registry.get(kind).normalizeSymbol(raw))
                .describedAs("%s normalizing %s", kind, raw)
                .isEqualTo(Symbol("BTC", "USDT"))
        }
        // Kraken's linear perpetuals are USD-quoted, so it is checked separately rather than forced.
        assertThat(registry.get(SourceKind.KRAKEN_FUTURES).normalizeSymbol("PF_XBTUSD"))
            .isEqualTo(Symbol("BTC", "USD"))
    }
}
