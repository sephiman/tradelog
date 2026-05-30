// SPDX-License-Identifier: AGPL-3.0-only
package com.sephilabs.tradelog.common

import java.math.BigDecimal
import java.math.RoundingMode

/**
 * Monetary helpers for PnL/fees/funding amounts (USDT for now).
 *
 * Crypto sizes and prices need more than 2 decimal places, so amounts are kept at
 * [SCALE] = 8. Realized PnL, fees and funding are stored as separate summable values;
 * this object only normalizes scale, it never consolidates them.
 */
object Usdt {
    const val SCALE: Int = 8

    fun normalize(value: BigDecimal): BigDecimal = value.setScale(SCALE, RoundingMode.HALF_EVEN)

}
