// SPDX-License-Identifier: AGPL-3.0-only
package com.sephilabs.tradelog.capital

import com.fasterxml.jackson.annotation.JsonFormat
import jakarta.validation.Valid
import jakarta.validation.constraints.NotBlank
import jakarta.validation.constraints.Size
import java.math.BigDecimal

/** A stored capital value for one exchange. */
data class CapitalEntryDto(
    val exchange: String,
    @JsonFormat(shape = JsonFormat.Shape.STRING) val amount: BigDecimal,
    val entryMode: String,
)

/** The two configurable risk percentages (e.g. 1 and 2 meaning 1% / 2%). */
data class RiskPercentsDto(
    @JsonFormat(shape = JsonFormat.Shape.STRING) val pct1: BigDecimal,
    @JsonFormat(shape = JsonFormat.Shape.STRING) val pct2: BigDecimal,
)

/**
 * Everything the Settings card and the Dashboard block need:
 * stored capital entries, the risk percentages, and the full list of exchanges
 * the user can set capital for (traded venues ∪ configured data sources).
 */
data class CapitalSettingsDto(
    val entries: List<CapitalEntryDto>,
    val riskPercents: RiskPercentsDto,
    val knownExchanges: List<String>,
)

data class CapitalEntryInput(
    @field:NotBlank(message = "validation.required")
    @field:Size(max = 80, message = "validation.too.long")
    val exchange: String,

    /** Null/blank/zero removes the exchange's capital; a positive value upserts it. */
    val amount: BigDecimal? = null,
)

data class RiskPercentsInput(
    val pct1: BigDecimal,
    val pct2: BigDecimal,
)

data class UpdateCapitalRequest(
    @field:Valid
    val entries: List<CapitalEntryInput> = emptyList(),

    @field:Valid
    val riskPercents: RiskPercentsInput,
)
