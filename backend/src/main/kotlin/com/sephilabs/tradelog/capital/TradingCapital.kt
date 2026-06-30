// SPDX-License-Identifier: AGPL-3.0-only
package com.sephilabs.tradelog.capital

import com.sephilabs.tradelog.common.TimestampedEntity
import jakarta.persistence.Column
import jakarta.persistence.Entity
import jakarta.persistence.Id
import jakarta.persistence.Table
import org.springframework.data.jpa.repository.JpaRepository
import java.math.BigDecimal
import java.util.UUID

/** How a capital value was produced. MANUAL today; API once balances can be fetched from the exchange. */
enum class CapitalEntryMode { MANUAL, API }

/** The current trading capital for one exchange within a profile, in USDT. No history — overwrite on change. */
@Entity
@Table(name = "trading_capital")
class TradingCapital(
    @Id
    @Column(name = "id", nullable = false, updatable = false)
    var id: UUID = UUID.randomUUID(),

    @Column(name = "profile_id", nullable = false, updatable = false)
    var profileId: UUID,

    @Column(name = "exchange", nullable = false, length = 80, updatable = false)
    var exchange: String,

    @Column(name = "amount", nullable = false, precision = 38, scale = 8)
    var amount: BigDecimal,

    @Column(name = "entry_mode", nullable = false, length = 16)
    var entryMode: String = CapitalEntryMode.MANUAL.name,
) : TimestampedEntity()

/** The two configurable risk percentages for a profile (one row per profile). */
@Entity
@Table(name = "capital_risk_settings")
class CapitalRiskSettings(
    @Id
    @Column(name = "profile_id", nullable = false, updatable = false)
    var profileId: UUID,

    @Column(name = "risk_pct_1", nullable = false, precision = 6, scale = 3)
    var riskPct1: BigDecimal,

    @Column(name = "risk_pct_2", nullable = false, precision = 6, scale = 3)
    var riskPct2: BigDecimal,
) : TimestampedEntity()

interface TradingCapitalRepository : JpaRepository<TradingCapital, UUID> {
    fun findAllByProfileIdOrderByExchangeAsc(profileId: UUID): List<TradingCapital>
    fun findByProfileIdAndExchange(profileId: UUID, exchange: String): TradingCapital?
}

interface CapitalRiskSettingsRepository : JpaRepository<CapitalRiskSettings, UUID>
