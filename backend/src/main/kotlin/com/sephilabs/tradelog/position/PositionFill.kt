// SPDX-License-Identifier: AGPL-3.0-only
package com.sephilabs.tradelog.position

import jakarta.persistence.Column
import jakarta.persistence.Entity
import jakarta.persistence.EnumType
import jakarta.persistence.Enumerated
import jakarta.persistence.Id
import jakarta.persistence.Table
import java.math.BigDecimal
import java.time.Instant
import java.util.UUID

enum class FillAction { OPEN, ADD, REDUCE, CLOSE }
enum class FillSide { BUY, SELL }

/** One leg of a position lifecycle (open / add / reduce / close), for the operations view. */
@Entity
@Table(name = "position_fills")
class PositionFill(
    @Id
    @Column(name = "id", nullable = false, updatable = false)
    var id: UUID = UUID.randomUUID(),

    @Column(name = "position_id", nullable = false, updatable = false)
    var positionId: UUID,

    @Column(name = "seq", nullable = false)
    var seq: Int,

    @Enumerated(EnumType.STRING)
    @Column(name = "action", nullable = false, length = 8)
    var action: FillAction,

    @Enumerated(EnumType.STRING)
    @Column(name = "side", nullable = false, length = 8)
    var side: FillSide,

    @Column(name = "ts", nullable = false)
    var ts: Instant,

    @Column(name = "price", nullable = false, precision = 38, scale = 18)
    var price: BigDecimal,

    @Column(name = "qty", nullable = false, precision = 38, scale = 18)
    var qty: BigDecimal,

    @Column(name = "value", precision = 38, scale = 8)
    var value: BigDecimal? = null,

    @Column(name = "fee", precision = 38, scale = 8)
    var fee: BigDecimal? = null,
)
