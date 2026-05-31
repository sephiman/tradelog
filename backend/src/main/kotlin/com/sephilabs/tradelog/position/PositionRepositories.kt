// SPDX-License-Identifier: AGPL-3.0-only
package com.sephilabs.tradelog.position

import com.sephilabs.tradelog.datasource.SourceKind
import org.springframework.data.jpa.repository.JpaRepository
import org.springframework.data.jpa.repository.JpaSpecificationExecutor
import org.springframework.data.jpa.repository.Modifying
import org.springframework.data.jpa.repository.Query
import org.springframework.data.repository.query.Param
import org.springframework.transaction.annotation.Transactional
import java.math.BigDecimal
import java.time.Instant
import java.util.UUID

/** Lightweight identity used to detect a file-import row overlapping an already-stored position. */
interface PositionKeyView {
    val symbolBase: String
    val symbolQuote: String
    val side: PositionSide
    val openedAt: Instant
    val closedAt: Instant
}

/**
 * Projection feeding the analytics dashboard: the raw closed-position facts the frontend needs to
 * compute every metric client-side. Net result is derived as realizedPnl − fees − funding.
 */
interface ClosedPositionSummary {
    val id: UUID
    val source: SourceKind
    val exchange: String?
    val symbolBase: String
    val symbolQuote: String
    val side: PositionSide
    val openedAt: Instant
    val closedAt: Instant
    val realizedPnl: BigDecimal
    val netPnl: BigDecimal
    val fees: BigDecimal
    val funding: BigDecimal
}

interface PositionRepository : JpaRepository<Position, UUID>, JpaSpecificationExecutor<Position> {

    fun findByIdAndProfileId(id: UUID, profileId: UUID): Position?

    fun findByDataSourceIdAndExternalId(dataSourceId: UUID, externalId: String): Position?

    /** All closed positions in the profile, oldest close first, for the analytics dashboard. */
    fun findAllByProfileIdOrderByClosedAtAsc(profileId: UUID): List<ClosedPositionSummary>

    /** All positions in the profile that did NOT come from [dataSourceId] — for cross-source overlap detection. */
    fun findAllByProfileIdAndDataSourceIdNot(profileId: UUID, dataSourceId: UUID): List<PositionKeyView>

    fun countByDataSourceId(dataSourceId: UUID): Long

    @Query("SELECT DISTINCT p.exchange FROM Position p WHERE p.profileId = :profileId AND p.exchange IS NOT NULL ORDER BY p.exchange")
    fun findDistinctExchanges(@Param("profileId") profileId: UUID): List<String>
}

interface PositionFillRepository : JpaRepository<PositionFill, UUID> {
    fun findAllByPositionIdOrderBySeqAsc(positionId: UUID): List<PositionFill>

    @Modifying
    @Transactional
    fun deleteByPositionId(positionId: UUID)

    fun findAllByPositionIdIn(positionIds: Collection<UUID>): List<PositionFill>
}

interface PositionTagRepository : JpaRepository<PositionTag, PositionTagId> {
    fun findAllByIdPositionIdIn(positionIds: Collection<UUID>): List<PositionTag>
    fun findByIdPositionIdAndIdGroupId(positionId: UUID, groupId: UUID): PositionTag?

    @Modifying
    @Transactional
    fun deleteByIdPositionIdAndIdGroupId(positionId: UUID, groupId: UUID)

    @Modifying
    @Transactional
    fun deleteByIdGroupIdAndIdPositionIdIn(groupId: UUID, positionIds: Collection<UUID>)
}
