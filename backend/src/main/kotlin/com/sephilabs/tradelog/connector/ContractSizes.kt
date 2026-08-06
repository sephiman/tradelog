// SPDX-License-Identifier: AGPL-3.0-only
package com.sephilabs.tradelog.connector

import com.fasterxml.jackson.databind.JsonNode
import java.math.BigDecimal

/** How much of the base asset one contract represents, per the venue's own symbol. */
class ContractSizes private constructor(private val bySymbol: Map<String, BigDecimal>) {

    val size: Int get() = bySymbol.size

    /** Base asset per contract for [symbol]; 1 when the venue did not report one. */
    fun of(symbol: String): BigDecimal = bySymbol[symbol.uppercase()] ?: BigDecimal.ONE

    companion object {
        val NONE = ContractSizes(emptyMap())

        /** Sizes stated directly, for tests and for venues whose sizes are a documented constant. */
        fun of(sizes: Map<String, BigDecimal>): ContractSizes =
            ContractSizes(sizes.mapKeys { it.key.uppercase() })

        /** Read a public instrument listing. A missing or non-positive size falls back to 1, not 0. */
        fun from(
            root: JsonNode?,
            rowPaths: List<String>,
            symbolKeys: List<String>,
            sizeKeys: List<String>,
        ): ContractSizes {
            if (root == null) return NONE
            val out = HashMap<String, BigDecimal>()
            for (row in root.rows(rowPaths)) {
                val symbol = row.text(symbolKeys)?.uppercase() ?: continue
                val size = row.dec(sizeKeys) ?: continue
                if (size.signum() > 0) out[symbol] = size
            }
            return ContractSizes(out)
        }
    }
}
