// SPDX-License-Identifier: AGPL-3.0-only
package com.sephilabs.tradelog.capital

import com.fasterxml.jackson.annotation.JsonFormat
import jakarta.validation.Valid
import jakarta.validation.constraints.NotBlank
import jakarta.validation.constraints.Size
import java.math.BigDecimal
import java.time.LocalDate
import java.util.UUID

/** The two configurable risk percentages (e.g. 1 and 2 meaning 1% / 2%). */
data class RiskPercentsDto(
    @JsonFormat(shape = JsonFormat.Shape.STRING) val pct1: BigDecimal,
    @JsonFormat(shape = JsonFormat.Shape.STRING) val pct2: BigDecimal,
)

/**
 * The estimated CURRENT capital of one exchange: its latest anchor carried forward with the net
 * PnL of trades closed since. [amount] is null when the exchange has no anchor yet (no history).
 */
data class CapitalEntryDto(
    val exchange: String,
    @JsonFormat(shape = JsonFormat.Shape.STRING) val amount: BigDecimal?,
    val anchorDate: LocalDate?,
    @JsonFormat(shape = JsonFormat.Shape.STRING) val anchorAmount: BigDecimal?,
)

/**
 * Everything the Capital page header, the Settings card and the Dashboard capital & risk block
 * need: estimated current capital per exchange, the risk percentages, the snapshot-job frequency,
 * and the exchanges the user can anchor capital for (traded venues ∪ configured data sources).
 */
data class CapitalOverviewDto(
    val entries: List<CapitalEntryDto>,
    @JsonFormat(shape = JsonFormat.Shape.STRING) val total: BigDecimal,
    val riskPercents: RiskPercentsDto,
    val snapshotFrequency: SnapshotFrequency,
    val knownExchanges: List<String>,
    val hasAnchors: Boolean,
    /** The owner's IANA time zone — governs every day boundary used by this feature. */
    val timeZone: String,
)

data class RiskPercentsInput(
    val pct1: BigDecimal,
    val pct2: BigDecimal,
)

data class UpdateCapitalSettingsRequest(
    @field:Valid
    val riskPercents: RiskPercentsInput,
    val snapshotFrequency: SnapshotFrequency = SnapshotFrequency.DAILY,
)

/** One anchor (MANUAL snapshot row): the asserted capital of one exchange at the start of [date]. */
data class AdjustmentDto(
    val id: UUID,
    val date: LocalDate,
    val exchange: String,
    @JsonFormat(shape = JsonFormat.Shape.STRING) val amount: BigDecimal,
)

data class AdjustmentEntryInput(
    @field:NotBlank(message = "validation.required")
    @field:Size(max = 80, message = "validation.too.long")
    val exchange: String,

    /** Null removes this exchange's anchor at the request date (the day reverts to auto-carried). */
    val amount: BigDecimal? = null,
)

/** Upserts anchors for one date: a balance per exchange, as entered on the Capital page. */
data class SaveAdjustmentsRequest(
    val date: LocalDate,
    @field:Valid
    val entries: List<AdjustmentEntryInput> = emptyList(),
)

data class PatchAdjustmentRequest(
    val date: LocalDate? = null,
    val amount: BigDecimal? = null,
)

/** One exchange's stored value within a snapshot day; [manual] marks it as an anchor. */
data class SnapshotValueDto(
    val exchange: String,
    @JsonFormat(shape = JsonFormat.Shape.STRING) val amount: BigDecimal,
    val manual: Boolean,
)

data class SnapshotDayDto(
    val date: LocalDate,
    val values: List<SnapshotValueDto>,
)

data class SnapshotSeriesDto(
    val days: List<SnapshotDayDto>,
    val exchanges: List<String>,
)

/** What a snapshot backfill/recompute did: AUTO rows written, refreshed, or dropped. */
data class RecomputeResult(
    val created: Int,
    val updated: Int,
    val deleted: Int,
)

/**
 * Real ROI for a period: net PnL of trades closed within it (and at/after the most recent anchor)
 * divided by the capital at the first day of the period. All-null when unavailable — no
 * snapshot/adjustment at or before the period start, or a zero denominator.
 */
data class RoiDto(
    /** Fraction, e.g. 0.052 = +5.2%. */
    @JsonFormat(shape = JsonFormat.Shape.STRING) val roi: BigDecimal?,
    @JsonFormat(shape = JsonFormat.Shape.STRING) val startCapital: BigDecimal?,
    @JsonFormat(shape = JsonFormat.Shape.STRING) val netPnl: BigDecimal?,
    /** The anchor that truncated the numerator, when one falls inside the period. */
    val cutDate: LocalDate?,
)
