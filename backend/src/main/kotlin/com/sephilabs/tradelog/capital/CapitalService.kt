// SPDX-License-Identifier: AGPL-3.0-only
package com.sephilabs.tradelog.capital

import com.sephilabs.tradelog.common.Usdt
import com.sephilabs.tradelog.common.errors.AppException
import com.sephilabs.tradelog.datasource.DataSourceRepository
import com.sephilabs.tradelog.position.PositionRepository
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import java.math.BigDecimal
import java.util.UUID

@Service
class CapitalService(
    private val capital: TradingCapitalRepository,
    private val settings: CapitalRiskSettingsRepository,
    private val positions: PositionRepository,
    private val dataSources: DataSourceRepository,
) {
    companion object {
        val DEFAULT_PCT_1: BigDecimal = BigDecimal.ONE
        val DEFAULT_PCT_2: BigDecimal = BigDecimal("2")
        private val MAX_PCT = BigDecimal("100")
    }

    @Transactional(readOnly = true)
    fun get(profileId: UUID): CapitalSettingsDto {
        val entries = capital.findAllByProfileIdOrderByExchangeAsc(profileId).map { it.toDto() }
        val risk = settings.findById(profileId).orElse(null)
        val riskDto = RiskPercentsDto(
            pct1 = risk?.riskPct1 ?: DEFAULT_PCT_1,
            pct2 = risk?.riskPct2 ?: DEFAULT_PCT_2,
        )
        return CapitalSettingsDto(entries, riskDto, knownExchanges(profileId))
    }

    @Transactional
    fun update(profileId: UUID, request: UpdateCapitalRequest): CapitalSettingsDto {
        val pct1 = normalizePct(request.riskPercents.pct1)
        val pct2 = normalizePct(request.riskPercents.pct2)

        val row = settings.findById(profileId).orElseGet {
            CapitalRiskSettings(profileId = profileId, riskPct1 = DEFAULT_PCT_1, riskPct2 = DEFAULT_PCT_2)
        }
        row.riskPct1 = pct1
        row.riskPct2 = pct2
        settings.save(row)

        for (entry in request.entries) {
            val exchange = entry.exchange.trim()
            if (exchange.isEmpty()) continue
            val amount = entry.amount
            val existing = capital.findByProfileIdAndExchange(profileId, exchange)
            if (amount == null || amount.signum() <= 0) {
                // Blank/zero/negative capital means "not set" — drop it so it stays out of totals.
                if (existing != null) capital.delete(existing)
                continue
            }
            val normalized = Usdt.normalize(amount)
            if (existing != null) {
                existing.amount = normalized
                existing.entryMode = CapitalEntryMode.MANUAL.name
            } else {
                capital.save(
                    TradingCapital(
                        profileId = profileId,
                        exchange = exchange,
                        amount = normalized,
                        entryMode = CapitalEntryMode.MANUAL.name,
                    ),
                )
            }
        }
        return get(profileId)
    }

    /** Exchanges the user can set capital for: traded venues ∪ configured data-source venues. */
    private fun knownExchanges(profileId: UUID): List<String> {
        val traded = positions.findDistinctExchanges(profileId)
        val sources = dataSources.findAllByProfileIdOrderByCreatedAtAsc(profileId)
            .mapNotNull { it.kind.venueLabel }
        return (traded + sources).distinct().sorted()
    }

    private fun normalizePct(value: BigDecimal): BigDecimal {
        if (value.signum() < 0 || value > MAX_PCT) throw AppException.badRequest("INVALID_PARAMETER", "riskPercent")
        return value.stripTrailingZeros().let { if (it.scale() < 0) it.setScale(0) else it }
    }

    private fun TradingCapital.toDto() = CapitalEntryDto(exchange, amount, entryMode)
}
