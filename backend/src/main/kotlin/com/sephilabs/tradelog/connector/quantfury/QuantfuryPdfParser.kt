// SPDX-License-Identifier: AGPL-3.0-only
package com.sephilabs.tradelog.connector.quantfury

import com.sephilabs.tradelog.common.errors.AppException
import com.sephilabs.tradelog.connector.RawFill
import org.apache.pdfbox.Loader
import org.apache.pdfbox.text.PDFTextStripper
import java.io.InputStream
import java.math.BigDecimal
import java.time.Instant
import java.time.LocalDateTime
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter
import java.util.Locale

/**
 * Parses the "Closed Positions" rows out of a Quantfury "Trading History Report" PDF into raw legs.
 *
 * Each leg line looks like:
 *   `ETH/USDT  28/09/2025 5:01:15 PM UTC  BUY (open)  4,033.01  0.12397688 ETH  500.00  [+2.81]`
 *   `NFLX  31/07/2026 1:57:08 PM UTC  BUY (open)  $72.10  17.7531 shares  $1,280.00  [+$74.74]`
 *
 * We deliberately ignore the printed "Position PnL" column — its text-extraction alignment is
 * unreliable (it drifts onto neighbouring rows). PnL is instead computed from the leg prices by the
 * reconstructor/connector, which is exact for Quantfury (spread-based prices, zero commission).
 *
 * For crypto rows the leftmost column alternates between "Symbol"/"Total"/pair labels and is not
 * trusted; the base asset is taken from the asset code after the quantity. Stock rows
 * ("Stocks & ETFs") measure quantity in `shares`, so the ticker only exists in the leftmost column
 * and is read from there. Quantfury accounts are USDT-denominated, so the quote is always USDT
 * even where the report prints `/USD` pair labels or `$` glyphs.
 */
object QuantfuryPdfParser {

    private val DATE_TIME = Regex("""(\d{1,2}/\d{1,2}/\d{4})\s+(\d{1,2}:\d{2}:\d{2})\s+(AM|PM)\s+UTC""")
    private val ACTION = Regex("""(BUY|SELL)\s*\((open|add|reduce|close)\)""", RegexOption.IGNORE_CASE)
    // After the action: price, quantity, asset code, value. Prices/quantities may carry thousands
    // commas; the value is prefixed by a currency glyph that varies by quote (₮ for USDT, $ for USD),
    // so any non-digit/non-space run before the value digits is tolerated and dropped.
    private val NUMBERS = Regex("""([\d.,]+)\s+([\d.,]+)\s+([A-Z]{2,10}|shares)\s+[^\d\s]*([\d.,]+)""")
    private val TICKER = Regex("""^([A-Z][A-Z0-9.]{0,9})\b""")

    private val FORMATTER: DateTimeFormatter =
        DateTimeFormatter.ofPattern("d/M/uuuu h:mm:ss a", Locale.ENGLISH)

    fun parse(input: InputStream): List<RawFill> {
        val text = Loader.loadPDF(input.readBytes()).use { doc ->
            PDFTextStripper().apply { sortByPosition = true }.getText(doc)
        }
        val fills = mutableListOf<RawFill>()
        for (line in text.lineSequence()) {
            val action = ACTION.find(line) ?: continue
            val dt = DATE_TIME.find(line) ?: continue
            val after = line.substring(action.range.last + 1)
            val nums = NUMBERS.find(after) ?: continue

            val side = action.groupValues[1].uppercase()
            val price = number(nums.groupValues[1]) ?: continue
            val qty = number(nums.groupValues[2]) ?: continue
            val unit = nums.groupValues[3]
            val ts = parseTimestamp(dt.groupValues[1], dt.groupValues[2], dt.groupValues[3]) ?: continue

            val base = if (unit.equals("shares", ignoreCase = true)) {
                // Stock rows carry no asset code after the quantity
                TICKER.find(line.substring(0, dt.range.first).trim())?.groupValues?.get(1) ?: continue
            } else {
                unit.uppercase()
            }

            fills += RawFill(
                symbol = "$base/USDT",
                ts = ts,
                buy = side == "BUY",
                price = price,
                qty = qty,
            )
        }
        if (fills.isEmpty()) throw AppException.badRequest("IMPORT_NO_POSITIONS")
        return fills
    }

    private fun number(raw: String): BigDecimal? =
        raw.replace(Regex("[^0-9.\\-]"), "").takeIf { it.isNotEmpty() }?.toBigDecimalOrNull()

    private fun parseTimestamp(date: String, time: String, meridiem: String): Instant? = try {
        LocalDateTime.parse("$date ${time} ${meridiem.uppercase()}", FORMATTER).toInstant(ZoneOffset.UTC)
    } catch (_: Exception) {
        null
    }
}
