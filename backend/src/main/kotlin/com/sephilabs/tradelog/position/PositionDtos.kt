// SPDX-License-Identifier: AGPL-3.0-only
package com.sephilabs.tradelog.position

import com.fasterxml.jackson.annotation.JsonFormat
import com.sephilabs.tradelog.datasource.SourceKind
import jakarta.validation.constraints.Size
import java.math.BigDecimal
import java.time.Instant
import java.util.UUID

data class PositionTagView(
    val groupId: UUID,
    val groupCode: String,
    val groupName: String,
    val tagId: UUID,
    val tagName: String,
)

data class PositionFillDto(
    val seq: Int,
    val action: FillAction,
    val side: FillSide,
    val ts: Instant,
    @JsonFormat(shape = JsonFormat.Shape.STRING) val price: BigDecimal,
    @JsonFormat(shape = JsonFormat.Shape.STRING) val qty: BigDecimal,
    @JsonFormat(shape = JsonFormat.Shape.STRING) val value: BigDecimal?,
    @JsonFormat(shape = JsonFormat.Shape.STRING) val fee: BigDecimal?,
) {
    companion object {
        fun of(f: PositionFill) = PositionFillDto(f.seq, f.action, f.side, f.ts, f.price, f.qty, f.value, f.fee)
    }
}

data class PositionDto(
    val id: UUID,
    val source: SourceKind,
    val exchange: String?,
    val symbolBase: String,
    val symbolQuote: String,
    val side: PositionSide,
    val openedAt: Instant,
    val closedAt: Instant,
    @JsonFormat(shape = JsonFormat.Shape.STRING) val qty: BigDecimal,
    @JsonFormat(shape = JsonFormat.Shape.STRING) val entryPrice: BigDecimal,
    @JsonFormat(shape = JsonFormat.Shape.STRING) val exitPrice: BigDecimal,
    @JsonFormat(shape = JsonFormat.Shape.STRING) val realizedPnl: BigDecimal,
    @JsonFormat(shape = JsonFormat.Shape.STRING) val netPnl: BigDecimal,
    @JsonFormat(shape = JsonFormat.Shape.STRING) val fees: BigDecimal,
    @JsonFormat(shape = JsonFormat.Shape.STRING) val funding: BigDecimal,
    val pnlCurrency: String,
    val note: String?,
    val tags: List<PositionTagView>,
    val fillCount: Int,
)

data class PositionDetailDto(
    val position: PositionDto,
    val fills: List<PositionFillDto>,
)

/**
 * Lightweight closed-position row for the analytics dashboard. [netPnl] is the bottom line
 * (gross realizedPnl − fees − funding), pre-computed; amounts are JSON strings to preserve scale.
 */
data class ClosedPositionSummaryDto(
    val id: UUID,
    val source: SourceKind,
    val exchange: String?,
    val symbolBase: String,
    val symbolQuote: String,
    val side: PositionSide,
    val openedAt: Instant,
    val closedAt: Instant,
    @JsonFormat(shape = JsonFormat.Shape.STRING) val realizedPnl: BigDecimal,
    @JsonFormat(shape = JsonFormat.Shape.STRING) val netPnl: BigDecimal,
    @JsonFormat(shape = JsonFormat.Shape.STRING) val fees: BigDecimal,
    @JsonFormat(shape = JsonFormat.Shape.STRING) val funding: BigDecimal,
)

data class NoteRequest(
    @field:Size(max = 4000, message = "validation.too.long")
    val note: String? = null,
)

data class SetTagRequest(
    val tagId: UUID,
)

/**
 * Set (or clear) one tag group on many positions at once. Two selection modes:
 *  - [positionIds] non-empty → apply to exactly those positions (explicit row selection).
 *  - otherwise → apply to every position matching [filters] (the current list filters).
 * A null [tagId] clears the group link instead of assigning a tag.
 */
data class BulkSetTagRequest(
    val tagId: UUID? = null,
    val positionIds: List<UUID>? = null,
    val filters: BulkTagFilters? = null,
)

data class BulkTagFilters(
    val symbol: String? = null,
    val side: PositionSide? = null,
    val source: SourceKind? = null,
    val exchange: String? = null,
    val from: Instant? = null,
    val to: Instant? = null,
    val tagId: UUID? = null,
    val untaggedGroupId: UUID? = null,
)

data class BulkTagResult(
    val updated: Int,
)
