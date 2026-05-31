// SPDX-License-Identifier: AGPL-3.0-only
package com.sephilabs.tradelog.connector.journal

import com.fasterxml.jackson.dataformat.csv.CsvMapper
import com.fasterxml.jackson.dataformat.csv.CsvSchema
import com.sephilabs.tradelog.common.errors.AppException
import com.sephilabs.tradelog.connector.FileImportConnector
import com.sephilabs.tradelog.connector.PositionRecord
import com.sephilabs.tradelog.connector.Symbol
import com.sephilabs.tradelog.connector.Symbols
import com.sephilabs.tradelog.datasource.SourceKind
import com.sephilabs.tradelog.position.PositionSide
import org.springframework.stereotype.Component
import java.io.InputStream
import java.math.BigDecimal
import java.security.MessageDigest
import java.time.Instant
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.OffsetDateTime
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter

/**
 * Imports closed positions from tradelog's canonical "trade journal" CSV — the format documented in
 * the UI's CSV-format reference, intended to be produced by any AI tool from arbitrary sources
 * (dead exchanges, hand-kept journals, PDFs). Each row is already one flat-to-flat closed position,
 * so no reconstruction is needed.
 *
 * Dialect: UTF-8, `;` column separator, `,` decimal mark (dot also accepted), ISO dates/instants
 * (UTC), RFC-4180 quoting. Header row is mandatory; columns are matched by name (order-independent).
 *
 * Realized PnL is the supplied `realized_pnl` when present (gross, before fees), otherwise computed
 * from the leg prices `(exit − entry) × qty`, negated for shorts — the same convention the
 * Quantfury and BingX connectors use. Fees and funding stay separate and summable.
 */
@Component
class JournalCsvConnector : FileImportConnector {

    override val kind = SourceKind.JOURNAL_CSV

    override fun normalizeSymbol(raw: String): Symbol = Symbols.split(raw)

    override fun parse(input: InputStream): List<PositionRecord> {
        val rows = readRows(input)
        if (rows.isEmpty()) throw AppException.badRequest("IMPORT_PARSE_FAILED")
        val occurrences = HashMap<String, Int>()
        return rows.mapIndexedNotNull { idx, row ->
            // Skip fully blank rows (trailing newlines etc.).
            if (row.values.all { it.isBlank() }) return@mapIndexedNotNull null
            // Row number is 1-based after the header, matching what a spreadsheet shows.
            val rowNum = idx + 2
            try {
                toRecord(row, occurrences, rowNum)
            } catch (e: AppException) {
                throw e
            } catch (e: Exception) {
                throw AppException.badRequest("IMPORT_ROW_INVALID", rowNum.toString(), e.message ?: "")
            }
        }
    }

    private fun toRecord(row: Map<String, String>, occurrences: MutableMap<String, Int>, rowNum: Int): PositionRecord {
        val symbol = normalizeSymbol(row.required("symbol"))
        val side = parseSide(row.required("side"))
        val openedAt = parseInstant(row.required("opened_at"))
        val closedAt = parseInstant(row.required("closed_at"))
        val qty = row.optional("quantity")?.let(::num) ?: BigDecimal.ONE
        val entryPrice = num(row.required("entry_price"))
        val exitPrice = num(row.required("exit_price"))
        val fees = row.optional("fees")?.let(::num) ?: BigDecimal.ZERO
        val funding = row.optional("funding")?.let(::num) ?: BigDecimal.ZERO
        val realizedPnl = row.optional("realized_pnl")?.let(::num) ?: computePnl(side, entryPrice, exitPrice, qty)
        val exchange = row.optional("exchange")?.take(MAX_EXCHANGE_LEN)
        val note = row.optional("note")

        // Day-only timestamps collide for same-symbol same-day trades, so the natural key includes the
        // numeric fields; an occurrence counter keeps genuine duplicates distinct while staying
        // deterministic for re-imports of the same file (idempotent upsert).
        val naturalKey = listOf(
            symbol.base, symbol.quote, side.name,
            openedAt.toEpochMilli(), closedAt.toEpochMilli(),
            qty.toPlainString(), entryPrice.toPlainString(), exitPrice.toPlainString(),
            realizedPnl.toPlainString(), fees.toPlainString(),
        ).joinToString("|")
        val occ = occurrences.merge(naturalKey, 1, Int::plus)!! - 1
        val externalId = "csv-${sha1(naturalKey)}-$occ"

        return PositionRecord(
            externalId = externalId,
            symbol = symbol,
            side = side,
            openedAt = openedAt,
            closedAt = closedAt,
            qty = qty,
            entryPrice = entryPrice,
            exitPrice = exitPrice,
            realizedPnl = realizedPnl,
            fees = fees,
            funding = funding,
            note = note,
            exchange = exchange,
            raw = rawOf(row),
            sourceRow = rowNum,
        )
    }

    /** Compact JSON of the row's non-empty cells, kept on the position for audit/reprocessing. */
    private fun rawOf(row: Map<String, String>): String =
        row.entries.filter { it.value.isNotBlank() }
            .joinToString(",", "{", "}") { (k, v) -> "${jsonStr(k)}:${jsonStr(v)}" }

    private fun jsonStr(s: String): String =
        "\"" + s.replace("\\", "\\\\").replace("\"", "\\\"") + "\""

    private fun computePnl(side: PositionSide, entry: BigDecimal, exit: BigDecimal, qty: BigDecimal): BigDecimal {
        val diff = if (side == PositionSide.LONG) exit.subtract(entry) else entry.subtract(exit)
        return diff.multiply(qty)
    }

    private fun readRows(input: InputStream): List<Map<String, String>> {
        val schema = CsvSchema.emptySchema()
            .withHeader()
            .withColumnSeparator(';')
            .withQuoteChar('"')
            .withComments() // ignore lines starting with '#'
        return try {
            MAPPER.readerFor(Map::class.java).with(schema)
                .readValues<Map<String, String>>(input.bufferedReader(Charsets.UTF_8))
                .readAll()
                .map { raw -> raw.entries.associate { (k, v) -> k.trim().lowercase() to v.trim() } }
        } catch (e: Exception) {
            throw AppException.badRequest("IMPORT_PARSE_FAILED", e.message ?: "")
        }
    }

    private fun Map<String, String>.required(col: String): String =
        this[col]?.takeIf { it.isNotBlank() } ?: throw AppException.badRequest("IMPORT_MISSING_COLUMN", col)

    private fun Map<String, String>.optional(col: String): String? =
        this[col]?.takeIf { it.isNotBlank() }

    private fun parseSide(raw: String): PositionSide = when (raw.trim().lowercase()) {
        "long", "buy", "l" -> PositionSide.LONG
        "short", "sell", "s" -> PositionSide.SHORT
        else -> throw AppException.badRequest("IMPORT_BAD_SIDE", raw)
    }

    /** Accepts ISO instants (`…Z`/offset), ISO local date-times, ISO dates, and `dd/MM/yyyy`. */
    private fun parseInstant(raw: String): Instant {
        val s = raw.trim()
        runCatching { return Instant.parse(s) }
        runCatching { return OffsetDateTime.parse(s).toInstant() }
        runCatching { return LocalDateTime.parse(s).toInstant(ZoneOffset.UTC) }
        runCatching { return LocalDate.parse(s).atStartOfDay(ZoneOffset.UTC).toInstant() }
        runCatching { return LocalDate.parse(s, DMY).atStartOfDay(ZoneOffset.UTC).toInstant() }
        throw AppException.badRequest("IMPORT_BAD_DATE", raw)
    }

    /**
     * Tolerant decimal parser: strips currency symbols/spaces, then resolves the decimal mark. When
     * both `.` and `,` appear the last one is the decimal separator (the other is a thousands group);
     * a lone `,` is treated as the decimal mark.
     */
    private fun num(raw: String): BigDecimal {
        var s = raw.trim().filter { it.isDigit() || it == '.' || it == ',' || it == '-' || it == '+' }
        if (s.isEmpty() || s == "-" || s == "+") throw NumberFormatException("not a number: '$raw'")
        val lastDot = s.lastIndexOf('.')
        val lastComma = s.lastIndexOf(',')
        s = when {
            lastDot >= 0 && lastComma >= 0 ->
                if (lastComma > lastDot) s.replace(".", "").replace(',', '.') // 1.234,56
                else s.replace(",", "")                                       // 1,234.56
            lastComma >= 0 -> s.replace(',', '.')                             // 1234,56
            else -> s                                                          // 1234.56 / 1234
        }
        return BigDecimal(s)
    }

    private fun sha1(s: String): String =
        MessageDigest.getInstance("SHA-1").digest(s.toByteArray(Charsets.UTF_8))
            .joinToString("") { "%02x".format(it) }
            .take(16)

    private companion object {
        const val MAX_EXCHANGE_LEN = 64
        val MAPPER = CsvMapper()
        val DMY: DateTimeFormatter = DateTimeFormatter.ofPattern("dd/MM/yyyy")
    }
}
