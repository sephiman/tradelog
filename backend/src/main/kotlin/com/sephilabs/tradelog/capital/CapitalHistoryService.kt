// SPDX-License-Identifier: AGPL-3.0-only
package com.sephilabs.tradelog.capital

import com.sephilabs.tradelog.common.Usdt
import com.sephilabs.tradelog.common.errors.AppException
import com.sephilabs.tradelog.identity.user.UserRepository
import com.sephilabs.tradelog.position.ClosedPnl
import com.sephilabs.tradelog.position.PositionRepository
import com.sephilabs.tradelog.profile.ProfileRepository
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import java.math.BigDecimal
import java.math.RoundingMode
import java.time.DayOfWeek
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.ZoneOffset
import java.util.SortedMap
import java.util.UUID

/** The estimated current capital of one exchange: its latest anchor carried forward to now. */
data class ExchangeEstimate(
    val exchange: String,
    val amount: BigDecimal,
    val anchorDate: LocalDate,
    val anchorAmount: BigDecimal,
)

/**
 * The capital-history engine. All rules live here:
 *
 * - An anchor (MANUAL snapshot row) dated D asserts the exchange's capital at the START of day D
 *   in the user's time zone. Trades closed on/after that instant count toward carried values;
 *   trades closed before it are settled into the anchor and never count again (the hard cut).
 * - The carried value at the start of day X is: latest anchor at/before X, plus the net PnL of the
 *   exchange's trades closed from the anchor day's start up to (exclusive) the start of X.
 * - AUTO rows materialize that estimate on the profile's cadence days (daily/weekly/monthly). They
 *   are recomputed whenever an anchor changes and refreshed by the scheduled job; MANUAL rows are
 *   never overwritten by either.
 * - Stored rows are the absolute source of truth for their date: reads (chart, ROI denominator)
 *   use the stored amount as-is; carry-forward re-derivation only ever starts from an anchor.
 */
@Service
class CapitalHistoryService(
    private val snapshots: CapitalSnapshotRepository,
    private val riskSettings: CapitalRiskSettingsRepository,
    private val positions: PositionRepository,
    private val profiles: ProfileRepository,
    private val users: UserRepository,
) {

    /** The profile owner's IANA time zone id — every day boundary in this feature uses it. */
    @Transactional(readOnly = true)
    fun userTimeZone(profileId: UUID): String {
        val profile = profiles.findById(profileId).orElseThrow { AppException.notFound("PROFILE_NOT_FOUND") }
        return users.findById(profile.userId).map { it.timeZone }.orElse("UTC")
    }

    @Transactional(readOnly = true)
    fun estimateNow(profileId: UUID): List<ExchangeEstimate> {
        val zone = zoneOf(profileId)
        val today = LocalDate.now(zone)
        val anchors = manualRows(profileId)
            .filter { !it.snapshotDate.isAfter(today) }
            .groupBy { it.exchange }
            .mapValues { (_, rows) -> rows.maxBy { it.snapshotDate } }
        if (anchors.isEmpty()) return emptyList()

        val earliest = anchors.values.minOf { it.snapshotDate }
        val trades = tradesByExchange(profileId, earliest.atStartOfDay(zone).toInstant())
        return anchors.map { (exchange, anchor) ->
            val cut = anchor.snapshotDate.atStartOfDay(zone).toInstant()
            val pnl = trades[exchange].orEmpty().filter { !it.closedAt.isBefore(cut) }.sumPnl()
            ExchangeEstimate(
                exchange = exchange,
                amount = Usdt.normalize(anchor.amount.add(pnl)),
                anchorDate = anchor.snapshotDate,
                anchorAmount = anchor.amount,
            )
        }.sortedBy { it.exchange }
    }

    @Transactional(readOnly = true)
    fun listAdjustments(profileId: UUID): List<AdjustmentDto> =
        snapshots.findAllByProfileIdAndSourceOrderBySnapshotDateDescExchangeAsc(profileId, SnapshotSource.MANUAL)
            .map { AdjustmentDto(it.id, it.snapshotDate, it.exchange, it.amount) }

    /**
     * Upserts the anchors of one date — a balance per exchange. A null amount removes that
     * exchange's anchor (the day reverts to auto-carried); zero is a valid balance ("no capital").
     */
    @Transactional
    fun saveAdjustments(profileId: UUID, request: SaveAdjustmentsRequest): List<AdjustmentDto> {
        val zone = zoneOf(profileId)
        if (request.date.isAfter(LocalDate.now(zone))) throw AppException.badRequest("INVALID_PARAMETER", "date")

        val touched = HashSet<String>()
        for (entry in request.entries) {
            val exchange = entry.exchange.trim()
            if (exchange.isEmpty()) continue
            val existing = snapshots.findByProfileIdAndExchangeAndSnapshotDate(profileId, exchange, request.date)
            val amount = entry.amount
            if (amount == null) {
                if (existing != null && existing.source == SnapshotSource.MANUAL) {
                    snapshots.delete(existing)
                    touched.add(exchange)
                }
                continue
            }
            if (amount.signum() < 0) throw AppException.badRequest("INVALID_PARAMETER", "amount")
            val normalized = Usdt.normalize(amount)
            if (existing != null) {
                existing.amount = normalized
                existing.source = SnapshotSource.MANUAL
                snapshots.save(existing)
            } else {
                snapshots.save(
                    CapitalSnapshot(
                        profileId = profileId,
                        exchange = exchange,
                        snapshotDate = request.date,
                        amount = normalized,
                        source = SnapshotSource.MANUAL,
                    ),
                )
            }
            touched.add(exchange)
        }
        if (touched.isNotEmpty()) {
            // Deletes must reach the DB before the recompute re-inserts an AUTO row on the same key.
            snapshots.flush()
            recomputeAutoSnapshots(profileId, touched)
        }
        return listAdjustments(profileId)
    }

    @Transactional
    fun patchAdjustment(profileId: UUID, id: UUID, request: PatchAdjustmentRequest): List<AdjustmentDto> {
        val row = snapshots.findByIdAndProfileId(id, profileId)?.takeIf { it.source == SnapshotSource.MANUAL }
            ?: throw AppException.notFound("ADJUSTMENT_NOT_FOUND")
        val zone = zoneOf(profileId)

        val newDate = request.date
        if (newDate != null && newDate != row.snapshotDate) {
            if (newDate.isAfter(LocalDate.now(zone))) throw AppException.badRequest("INVALID_PARAMETER", "date")
            val clash = snapshots.findByProfileIdAndExchangeAndSnapshotDate(profileId, row.exchange, newDate)
            if (clash != null) {
                if (clash.source == SnapshotSource.MANUAL) throw AppException.conflict("ADJUSTMENT_EXISTS")
                // A stale AUTO row occupies the target day; drop it now (flushed before the update
                // below reaches the DB) so the unique (profile, exchange, date) index is not tripped.
                snapshots.delete(clash)
                snapshots.flush()
            }
            row.snapshotDate = newDate
        }
        val newAmount = request.amount
        if (newAmount != null) {
            if (newAmount.signum() < 0) throw AppException.badRequest("INVALID_PARAMETER", "amount")
            row.amount = Usdt.normalize(newAmount)
        }
        snapshots.save(row)
        snapshots.flush()
        recomputeAutoSnapshots(profileId, setOf(row.exchange))
        return listAdjustments(profileId)
    }

    @Transactional
    fun deleteAdjustment(profileId: UUID, id: UUID): List<AdjustmentDto> {
        val row = snapshots.findByIdAndProfileId(id, profileId)?.takeIf { it.source == SnapshotSource.MANUAL }
            ?: throw AppException.notFound("ADJUSTMENT_NOT_FOUND")
        snapshots.delete(row)
        // The recompute may re-insert the same (exchange, date) as AUTO — the delete must land first.
        snapshots.flush()
        recomputeAutoSnapshots(profileId, setOf(row.exchange))
        return listAdjustments(profileId)
    }

    @Transactional(readOnly = true)
    fun snapshotSeries(profileId: UUID, from: LocalDate?, to: LocalDate?): SnapshotSeriesDto {
        val rows = if (from != null && to != null) {
            snapshots.findAllByProfileIdAndSnapshotDateBetweenOrderBySnapshotDateAscExchangeAsc(profileId, from, to)
        } else {
            snapshots.findAllByProfileIdOrderBySnapshotDateAscExchangeAsc(profileId)
                .filter { (from == null || !it.snapshotDate.isBefore(from)) && (to == null || !it.snapshotDate.isAfter(to)) }
        }
        val days = rows.groupBy { it.snapshotDate }.toSortedMap().map { (date, list) ->
            SnapshotDayDto(
                date = date,
                values = list.sortedBy { it.exchange }
                    .map { SnapshotValueDto(it.exchange, it.amount, it.source == SnapshotSource.MANUAL) },
            )
        }
        return SnapshotSeriesDto(days = days, exchanges = rows.map { it.exchange }.distinct().sorted())
    }

    /**
     * ROI of [from, to]: net PnL of trades closed within the period (and at/after the most recent
     * anchor — the hard cut) over the capital at the first day of the period. A null [from] means
     * all-time: each exchange starts at its first anchor. A null [exchange] spans every exchange
     * that has a capital value at the period start; the others are left out of both sides.
     */
    @Transactional(readOnly = true)
    fun roi(profileId: UUID, from: Instant?, to: Instant?, exchange: String?): RoiDto {
        val zone = zoneOf(profileId)
        val toInstant = to ?: Instant.now()
        val toDate = LocalDate.ofInstant(toInstant, zone)
        val anchorsByExchange = manualRows(profileId)
            .filter { (exchange == null || it.exchange == exchange) && !it.snapshotDate.isAfter(toDate) }
            .groupBy { it.exchange }
        if (anchorsByExchange.isEmpty()) return RoiDto(null, null, null, null)

        val fromDate = from?.let { LocalDate.ofInstant(it, zone) }
        val earliestAnchor = anchorsByExchange.values.flatten().minOf { it.snapshotDate }
        val trades = tradesByExchange(profileId, earliestAnchor.atStartOfDay(zone).toInstant())

        var denominator = BigDecimal.ZERO
        var numerator = BigDecimal.ZERO
        var included = false
        var cutDate: LocalDate? = null

        for ((ex, rows) in anchorsByExchange) {
            val anchors = rows.sortedBy { it.snapshotDate }
            val exTrades = trades[ex].orEmpty()

            val periodStart: Instant
            val startCapital: BigDecimal?
            if (fromDate != null) {
                periodStart = from!!
                // The period-start day's stored value is the truth; without one, carry the latest
                // anchor at/before that day forward to the start of that day.
                val stored = snapshots.findByProfileIdAndExchangeAndSnapshotDate(profileId, ex, fromDate)
                startCapital = stored?.amount ?: run {
                    val base = anchors.lastOrNull { !it.snapshotDate.isAfter(fromDate) } ?: return@run null
                    val baseStart = base.snapshotDate.atStartOfDay(zone).toInstant()
                    val dayStart = fromDate.atStartOfDay(zone).toInstant()
                    base.amount.add(
                        exTrades.filter { !it.closedAt.isBefore(baseStart) && it.closedAt.isBefore(dayStart) }.sumPnl(),
                    )
                }
            } else {
                periodStart = anchors.first().snapshotDate.atStartOfDay(zone).toInstant()
                startCapital = anchors.first().amount
            }
            if (startCapital == null) continue // no capital at the period start — out of both sides

            val cut = anchors.last() // latest anchor ≤ toDate (the list is already bounded by toDate)
            val cutStart = cut.snapshotDate.atStartOfDay(zone).toInstant()
            val lower = maxOf(periodStart, cutStart)
            numerator = numerator.add(
                exTrades.filter { !it.closedAt.isBefore(lower) && !it.closedAt.isAfter(toInstant) }.sumPnl(),
            )
            denominator = denominator.add(startCapital)
            included = true
            if (cutStart > periodStart && (cutDate == null || cut.snapshotDate.isAfter(cutDate))) {
                cutDate = cut.snapshotDate
            }
        }

        if (!included) return RoiDto(null, null, null, null)
        val roi = if (denominator.signum() == 0) null
        else numerator.divide(denominator, ROI_SCALE, RoundingMode.HALF_EVEN)
        return RoiDto(roi, Usdt.normalize(denominator), Usdt.normalize(numerator), cutDate)
    }

    /**
     * Re-derives every AUTO row from the anchors and stored trades: refreshes stale values, fills
     * the profile's cadence days, and drops rows that lost their basis (before the first anchor,
     * or the exchange no longer has anchors at all). MANUAL rows are never touched.
     */
    @Transactional
    fun recomputeAutoSnapshots(profileId: UUID, onlyExchanges: Set<String>? = null): RecomputeResult {
        var created = 0
        var updated = 0
        var deleted = 0
        val zone = zoneOf(profileId)
        val today = LocalDate.now(zone)
        val frequency = riskSettings.findById(profileId).map { it.snapshotFrequency }
            .orElse(SnapshotFrequency.DAILY)
        val rows = snapshots.findAllByProfileIdOrderBySnapshotDateAscExchangeAsc(profileId)
            .filter { onlyExchanges == null || it.exchange in onlyExchanges }
        if (rows.isEmpty()) return RecomputeResult(0, 0, 0)

        val earliestAnchor = rows.filter { it.source == SnapshotSource.MANUAL }.minOfOrNull { it.snapshotDate }
        val trades = if (earliestAnchor == null) emptyMap()
        else tradesByExchange(profileId, earliestAnchor.atStartOfDay(zone).toInstant())

        for ((exchange, exRows) in rows.groupBy { it.exchange }) {
            val anchors = exRows.filter { it.source == SnapshotSource.MANUAL }
                .associateTo(sortedMapOf()) { it.snapshotDate to it.amount }
            val autos = exRows.filter { it.source == SnapshotSource.AUTO }
            if (anchors.isEmpty()) {
                snapshots.deleteAll(autos)
                deleted += autos.size
                continue
            }
            val first = anchors.firstKey()
            val values = walkDaily(first, today, anchors, pnlByDay(trades[exchange].orEmpty(), zone))

            val targets = sortedSetOf<LocalDate>()
            cadenceDays(first, today, frequency).forEach { if (it !in anchors) targets.add(it) }
            autos.forEach {
                if (it.snapshotDate in first..today && it.snapshotDate !in anchors) targets.add(it.snapshotDate)
            }

            val autoByDate = autos.associateBy { it.snapshotDate }
            val stale = autos.filter { it.snapshotDate !in targets }
            snapshots.deleteAll(stale)
            deleted += stale.size
            for (date in targets) {
                val value = Usdt.normalize(values.getValue(date))
                val existing = autoByDate[date]
                if (existing == null) {
                    snapshots.save(
                        CapitalSnapshot(
                            profileId = profileId,
                            exchange = exchange,
                            snapshotDate = date,
                            amount = value,
                            source = SnapshotSource.AUTO,
                        ),
                    )
                    created++
                } else if (existing.amount.compareTo(value) != 0) {
                    existing.amount = value
                    snapshots.save(existing)
                    updated++
                }
            }
        }
        return RecomputeResult(created, updated, deleted)
    }

    /**
     * Start-of-day values from [first] (which must be an anchor) through [last]:
     * v[d] = anchor(d) if the day is anchored, else v[d-1] + net PnL closed during d-1.
     */
    private fun walkDaily(
        first: LocalDate,
        last: LocalDate,
        anchors: SortedMap<LocalDate, BigDecimal>,
        pnlByDay: Map<LocalDate, BigDecimal>,
    ): Map<LocalDate, BigDecimal> {
        val values = HashMap<LocalDate, BigDecimal>()
        if (first.isAfter(last)) return values
        var day = first
        var value = anchors.getValue(first)
        values[day] = value
        while (day.isBefore(last)) {
            val previous = day
            day = day.plusDays(1)
            value = anchors[day] ?: value.add(pnlByDay[previous] ?: BigDecimal.ZERO)
            values[day] = value
        }
        return values
    }

    private fun cadenceDays(from: LocalDate, to: LocalDate, frequency: SnapshotFrequency): List<LocalDate> {
        val days = ArrayList<LocalDate>()
        var day = from
        while (!day.isAfter(to)) {
            val hit = when (frequency) {
                SnapshotFrequency.DAILY -> true
                SnapshotFrequency.WEEKLY -> day.dayOfWeek == DayOfWeek.MONDAY
                SnapshotFrequency.MONTHLY -> day.dayOfMonth == 1
            }
            if (hit) days.add(day)
            day = day.plusDays(1)
        }
        return days
    }

    private fun pnlByDay(trades: List<ClosedPnl>, zone: ZoneId): Map<LocalDate, BigDecimal> =
        trades.groupBy { LocalDate.ofInstant(it.closedAt, zone) }.mapValues { (_, rows) -> rows.sumPnl() }

    private fun tradesByExchange(profileId: UUID, from: Instant): Map<String, List<ClosedPnl>> =
        positions.findAllByProfileIdAndClosedAtGreaterThanEqualAndDeletedAtIsNull(profileId, from)
            .filter { it.exchange != null }
            .groupBy { it.exchange!! }

    private fun manualRows(profileId: UUID) =
        snapshots.findAllByProfileIdAndSourceOrderBySnapshotDateDescExchangeAsc(profileId, SnapshotSource.MANUAL)

    private fun zoneOf(profileId: UUID): ZoneId =
        runCatching { ZoneId.of(userTimeZone(profileId)) }.getOrDefault(ZoneOffset.UTC)

    private fun List<ClosedPnl>.sumPnl(): BigDecimal = fold(BigDecimal.ZERO) { acc, t -> acc.add(t.netPnl) }

    private companion object {
        const val ROI_SCALE = 8
    }
}
