// SPDX-License-Identifier: AGPL-3.0-only
package com.sephilabs.tradelog.datasource

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

class VenuesTest {

    @Test
    fun `connector venue keeps the connector's own spelling`() {
        assertEquals("BingX", Venues.canonical("bingx"))
        assertEquals("BingX", Venues.canonical("BINGX"))
        assertEquals("BingX", Venues.canonical("  Bingx "))
        assertEquals("BitMart", Venues.canonical("bitmart"))
        assertEquals("Bitunix", Venues.canonical("BiTuNiX"))
        assertEquals("Quantfury", Venues.canonical("quantfury"))
    }

    @Test
    fun `punctuation and spacing do not make a second venue`() {
        assertEquals("BingX", Venues.canonical("Bing-X"))
        assertEquals("BingX", Venues.canonical("bing x"))
    }

    @Test
    fun `a connector venue wins over a spelling already in use`() =
        assertEquals("BingX", Venues.canonical("bingx", listOf("Bingx")))

    @Test
    fun `an unknown venue folds onto the spelling the profile already uses`() {
        assertEquals("FTX", Venues.canonical("ftx", listOf("Kraken", "FTX")))
        assertEquals("Kraken", Venues.canonical("KRAKEN", listOf("Kraken")))
    }

    @Test
    fun `an unknown venue nobody named yet keeps the user's spelling`() =
        assertEquals("Deribit", Venues.canonical("Deribit", listOf("Kraken", "FTX")))

    @Test
    fun `a different venue is not folded`() =
        assertEquals("FTX US", Venues.canonical("FTX US", listOf("FTX")))

    @Test
    fun `a name with no letters or digits is left alone`() =
        assertEquals("--", Venues.canonical("  --  ", listOf("BingX")))
}
