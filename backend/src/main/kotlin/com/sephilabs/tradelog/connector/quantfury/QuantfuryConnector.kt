// SPDX-License-Identifier: AGPL-3.0-only
package com.sephilabs.tradelog.connector.quantfury

import com.sephilabs.tradelog.connector.FileImportConnector
import com.sephilabs.tradelog.connector.PositionReconstructor
import com.sephilabs.tradelog.connector.PositionRecord
import com.sephilabs.tradelog.connector.Symbol
import com.sephilabs.tradelog.connector.Symbols
import com.sephilabs.tradelog.datasource.SourceKind
import org.springframework.stereotype.Component
import java.io.InputStream
import java.math.BigDecimal

/**
 * Quantfury "Trading History Report" PDF connector. Legs parsed from the PDF are folded into
 * flat-to-flat positions by the shared reconstructor; realized PnL is then computed from the leg
 * prices — exact for Quantfury, whose prices already include the spread and which charges no
 * commission, so fees and funding are always zero.
 */
@Component
class QuantfuryConnector : FileImportConnector {

    override val kind = SourceKind.QUANTFURY

    override fun normalizeSymbol(raw: String): Symbol = Symbols.split(raw)

    override fun parse(input: InputStream): List<PositionRecord> {
        val fills = QuantfuryPdfParser.parse(input)
        return PositionReconstructor.reconstruct(fills, ::normalizeSymbol).map { rec ->
            rec.copy(
                realizedPnl = PositionReconstructor.realizedFromPrices(rec),
                fees = BigDecimal.ZERO,
                funding = BigDecimal.ZERO,
            )
        }
    }
}
