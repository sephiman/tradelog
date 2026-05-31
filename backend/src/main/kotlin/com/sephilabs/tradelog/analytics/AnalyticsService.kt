// SPDX-License-Identifier: AGPL-3.0-only
package com.sephilabs.tradelog.analytics

import com.fasterxml.jackson.annotation.JsonFormat
import com.sephilabs.tradelog.position.PositionRepository
import com.sephilabs.tradelog.profile.ProfileRepository
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import java.math.BigDecimal
import java.time.LocalDate
import java.time.ZoneOffset
import java.util.UUID

data class PnlCumPoint(
    val date: LocalDate,
    @JsonFormat(shape = JsonFormat.Shape.STRING) val cumulative: BigDecimal,
)

data class PnlSeriesDto(
    val profileId: UUID,
    val profileName: String,
    val points: List<PnlCumPoint>,
)

@Service
class AnalyticsService(
    private val profiles: ProfileRepository,
    private val positions: PositionRepository,
) {

    /** Cumulative net realized PnL per profile (daily, UTC), for the dashboard chart. */
    @Transactional(readOnly = true)
    fun cumulativePnlPerProfile(userId: UUID): List<PnlSeriesDto> {
        val userProfiles = profiles.findAllByUserIdOrderByCreatedAtAsc(userId)
        if (userProfiles.isEmpty()) return emptyList()
        val pointsByProfile = positions.findAllByProfileIdInOrderByClosedAtAsc(userProfiles.map { it.id })
            .groupBy { it.profileId }

        return userProfiles.map { profile ->
            val raw = pointsByProfile[profile.id].orEmpty()
            // Net = realized PnL minus fees and funding (realizedPnl is gross; see PositionRecord).
            // Sum per UTC day, then accumulate.
            val perDay = sortedMapOf<LocalDate, BigDecimal>()
            for (p in raw) {
                val day = p.closedAt.atZone(ZoneOffset.UTC).toLocalDate()
                val net = p.realizedPnl.subtract(p.fees).subtract(p.funding)
                perDay[day] = (perDay[day] ?: BigDecimal.ZERO).add(net)
            }
            var running = BigDecimal.ZERO
            val series = perDay.map { (day, sum) ->
                running = running.add(sum)
                PnlCumPoint(day, running)
            }
            PnlSeriesDto(profile.id, profile.name, series)
        }
    }
}
