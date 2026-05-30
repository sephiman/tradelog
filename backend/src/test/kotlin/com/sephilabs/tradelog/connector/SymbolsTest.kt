// SPDX-License-Identifier: AGPL-3.0-only
package com.sephilabs.tradelog.connector

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

class SymbolsTest {

    @Test
    fun `concatenated usdt`() = assertEquals(Symbol("BTC", "USDT"), Symbols.split("BTCUSDT"))

    @Test
    fun `hyphen separated`() = assertEquals(Symbol("BTC", "USDT"), Symbols.split("BTC-USDT"))

    @Test
    fun `slash separated`() = assertEquals(Symbol("ETH", "USDT"), Symbols.split("ETH/USDT"))

    @Test
    fun `usd quote not greedily matched as usdt`() = assertEquals(Symbol("HBAR", "USD"), Symbols.split("HBAR/USD"))

    @Test
    fun `numeric prefixed base`() = assertEquals(Symbol("1000PEPE", "USDT"), Symbols.split("1000PEPEUSDT"))

    @Test
    fun `lowercase is normalized`() = assertEquals(Symbol("SOL", "USDT"), Symbols.split("solusdt"))
}
