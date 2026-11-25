// SPDX-License-Identifier: AGPL-3.0-only
package com.sephilabs.tradelog.position

import com.fasterxml.jackson.dataformat.csv.CsvGenerator
import com.fasterxml.jackson.dataformat.csv.CsvMapper
import com.fasterxml.jackson.dataformat.csv.CsvSchema
import java.io.Writer
import java.math.BigDecimal

/**
 * Writes positions in the trade-journal CSV dialect (`;` separator, `.` decimals, ISO UTC instants), so an
 * export of plain trades re-imports through the journal CSV connector. Columns past `note` are export-only;
 * the importer ignores them.
 */
object PositionCsv {

    private val COLUMNS = listOf(
        "symbol", "side", "opened_at", "closed_at", "quantity", "entry_price", "exit_price",
        "realized_pnl", "fees", "funding", "exchange", "note",
        "net_pnl", "pnl_currency", "volume", "leverage", "investment", "kind", "source", "tags",
    )

    private val SCHEMA: CsvSchema = CsvSchema.builder()
        .addColumns(COLUMNS, CsvSchema.ColumnType.STRING)
        .setColumnSeparator(';')
        .setUseHeader(true)
        .build()

    // Quote only cells that need it (separator, quote, line break) instead of Jackson's loose heuristic.
    private val MAPPER = CsvMapper.builder().enable(CsvGenerator.Feature.STRICT_CHECK_FOR_QUOTING).build()

    fun write(positions: List<PositionDto>, out: Writer) {
        MAPPER.writer(SCHEMA).writeValues(out).use { rows ->
            positions.forEach { rows.write(cellsOf(it)) }
        }
    }

    private fun cellsOf(p: PositionDto): List<String> = listOf(
        "${p.symbolBase}-${p.symbolQuote}",
        p.side.name,
        p.openedAt.toString(),
        p.closedAt.toString(),
        p.qty.plain(),
        p.entryPrice.plain(),
        p.exitPrice.plain(),
        p.realizedPnl.plain(),
        p.fees.plain(),
        p.funding.plain(),
        p.exchange.orEmpty(),
        p.note.orEmpty(),
        p.netPnl.plain(),
        p.pnlCurrency,
        effectiveVolume(p).plain(),
        p.leverage.plain(),
        p.investment.plain(),
        p.kind.name,
        p.source.name,
        p.tags.joinToString(", ") { it.tagName },
    )

    /** Same rule as the positions table: the recorded volume, else both legs' notional. */
    private fun effectiveVolume(p: PositionDto): BigDecimal? {
        if (p.volume != null) return p.volume
        val qty = p.qty ?: return null
        val entry = p.entryPrice ?: return null
        val exit = p.exitPrice ?: return null
        return qty * (entry + exit)
    }

    private fun BigDecimal?.plain(): String = this?.stripTrailingZeros()?.toPlainString().orEmpty()
}
