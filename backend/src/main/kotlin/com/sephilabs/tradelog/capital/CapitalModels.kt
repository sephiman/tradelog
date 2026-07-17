// SPDX-License-Identifier: AGPL-3.0-only
package com.sephilabs.tradelog.capital

import com.sephilabs.tradelog.common.TimestampedEntity
import jakarta.persistence.Column
import jakarta.persistence.Entity
import jakarta.persistence.EnumType
import jakarta.persistence.Enumerated
import jakarta.persistence.Id
import jakarta.persistence.Table
import org.springframework.data.jpa.repository.JpaRepository
import org.springframework.data.jpa.repository.Query
import org.springframework.data.repository.query.Param
import java.math.BigDecimal
import java.time.LocalDate
import java.util.UUID

/**
 * How a capital snapshot row was produced.
 *
 * MANUAL rows are the adjustment history: a balance the user asserted for that date. They are
 * ANCHORS — the truth at that date and a hard cut: PnL closed before an anchor no longer counts
 * after it (it is considered settled into that balance). AUTO rows are estimates the background
 * job carries forward from the latest anchor; they are never anchors and may be recomputed.
 */
enum class SnapshotSource { MANUAL, AUTO }

/** How often the background job materializes AUTO snapshots for a profile. */
enum class SnapshotFrequency { DAILY, WEEKLY, MONTHLY }

/**
 * The capital of one exchange within a profile at the START of [snapshotDate] in the owner's time
 * zone (i.e. before any day-[snapshotDate] trading), in USDT. The stored value is the absolute
 * source of truth for calculations on that date, whether auto-carried or manually entered.
 */
@Entity
@Table(name = "capital_snapshots")
class CapitalSnapshot(
    @Id
    @Column(name = "id", nullable = false, updatable = false)
    var id: UUID = UUID.randomUUID(),

    @Column(name = "profile_id", nullable = false, updatable = false)
    var profileId: UUID,

    @Column(name = "exchange", nullable = false, length = 80, updatable = false)
    var exchange: String,

    @Column(name = "snapshot_date", nullable = false)
    var snapshotDate: LocalDate,

    @Column(name = "amount", nullable = false, precision = 38, scale = 8)
    var amount: BigDecimal,

    @Enumerated(EnumType.STRING)
    @Column(name = "source", nullable = false, length = 16)
    var source: SnapshotSource = SnapshotSource.MANUAL,
) : TimestampedEntity()

/** The two configurable risk percentages and the snapshot-job frequency (one row per profile). */
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

    @Enumerated(EnumType.STRING)
    @Column(name = "snapshot_frequency", nullable = false, length = 16)
    var snapshotFrequency: SnapshotFrequency = SnapshotFrequency.DAILY,
) : TimestampedEntity()

interface CapitalSnapshotRepository : JpaRepository<CapitalSnapshot, UUID> {
    fun findAllByProfileIdOrderBySnapshotDateAscExchangeAsc(profileId: UUID): List<CapitalSnapshot>

    fun findAllByProfileIdAndSnapshotDateBetweenOrderBySnapshotDateAscExchangeAsc(
        profileId: UUID,
        from: LocalDate,
        to: LocalDate,
    ): List<CapitalSnapshot>

    fun findAllByProfileIdAndSourceOrderBySnapshotDateDescExchangeAsc(
        profileId: UUID,
        source: SnapshotSource,
    ): List<CapitalSnapshot>

    fun findByIdAndProfileId(id: UUID, profileId: UUID): CapitalSnapshot?

    fun findByProfileIdAndExchangeAndSnapshotDate(profileId: UUID, exchange: String, date: LocalDate): CapitalSnapshot?

    /** Profiles that have at least one anchor — the ones the snapshot job has anything to do for. */
    @Query("SELECT DISTINCT s.profileId FROM CapitalSnapshot s WHERE s.source = :source")
    fun findDistinctProfileIdsBySource(@Param("source") source: SnapshotSource): List<UUID>
}

interface CapitalRiskSettingsRepository : JpaRepository<CapitalRiskSettings, UUID>
