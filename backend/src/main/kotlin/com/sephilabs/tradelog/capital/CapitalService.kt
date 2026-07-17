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
    private val history: CapitalHistoryService,
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
    fun overview(profileId: UUID): CapitalOverviewDto {
        val estimates = history.estimateNow(profileId)
        val estimateByExchange = estimates.associateBy { it.exchange }
        val known = (knownExchanges(profileId) + estimates.map { it.exchange }).distinct().sorted()
        val entries = known.map { exchange ->
            val estimate = estimateByExchange[exchange]
            CapitalEntryDto(
                exchange = exchange,
                amount = estimate?.amount,
                anchorDate = estimate?.anchorDate,
                anchorAmount = estimate?.anchorAmount,
            )
        }
        val total = estimates.fold(BigDecimal.ZERO) { acc, e -> acc.add(e.amount) }
        val risk = settings.findById(profileId).orElse(null)
        return CapitalOverviewDto(
            entries = entries,
            total = Usdt.normalize(total),
            riskPercents = RiskPercentsDto(
                pct1 = risk?.riskPct1 ?: DEFAULT_PCT_1,
                pct2 = risk?.riskPct2 ?: DEFAULT_PCT_2,
            ),
            snapshotFrequency = risk?.snapshotFrequency ?: SnapshotFrequency.DAILY,
            knownExchanges = known,
            hasAnchors = estimates.isNotEmpty(),
            timeZone = history.userTimeZone(profileId),
        )
    }

    @Transactional
    fun updateSettings(profileId: UUID, request: UpdateCapitalSettingsRequest): CapitalOverviewDto {
        val pct1 = normalizePct(request.riskPercents.pct1)
        val pct2 = normalizePct(request.riskPercents.pct2)

        val row = settings.findById(profileId).orElseGet {
            CapitalRiskSettings(profileId = profileId, riskPct1 = DEFAULT_PCT_1, riskPct2 = DEFAULT_PCT_2)
        }
        row.riskPct1 = pct1
        row.riskPct2 = pct2
        val frequencyChanged = row.snapshotFrequency != request.snapshotFrequency
        row.snapshotFrequency = request.snapshotFrequency
        settings.save(row)
        // A new cadence changes which days the series materializes — re-derive right away rather
        // than leaving the chart stale until the next job run.
        if (frequencyChanged) history.recomputeAutoSnapshots(profileId)
        return overview(profileId)
    }

    /** Exchanges the user can anchor capital for: traded venues ∪ configured data-source venues. */
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
}
