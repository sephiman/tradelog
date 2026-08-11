// SPDX-License-Identifier: AGPL-3.0-only
package com.sephilabs.tradelog.connector.quantfury

import org.junit.jupiter.api.Test
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable
import java.io.File

/** Throwaway check against the real export in the repo root; not committed. */
class TempRealPdfCheck {

    @Test
    @EnabledIfEnvironmentVariable(named = "REAL_PDF", matches = ".+")
    fun `parse real pdf`() {
        val file = File(System.getenv("REAL_PDF"))
        val fills = QuantfuryPdfParser.parse(file.inputStream())
        println("FILLS=${fills.size}")
        val records = QuantfuryConnector().parse(file.inputStream())
        println("POSITIONS=${records.size}")
        records.filter { it.symbol.base == "NFLX" }.forEach {
            println("NFLX: side=${it.side} qty=${it.qty} entry=${it.entryPrice} pnl=${it.realizedPnl}")
        }
        println("SYMBOLS=" + records.map { "${it.symbol.base}/${it.symbol.quote}" }.distinct().sorted())
    }
}
