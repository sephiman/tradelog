// SPDX-License-Identifier: AGPL-3.0-only
package com.sephilabs.tradelog.position

import org.springframework.data.jpa.repository.JpaRepository
import org.springframework.data.jpa.repository.JpaSpecificationExecutor
import org.springframework.data.jpa.repository.Modifying
import org.springframework.transaction.annotation.Transactional
import java.math.BigDecimal
import java.time.Instant
import java.util.UUID

/** Projection for the cumulative realized-PnL-per-profile chart. */
interface PnlPoint {
    val profileId: UUID
    val closedAt: Instant
    val realizedPnl: BigDecimal
}

interface PositionRepository : JpaRepository<Position, UUID>, JpaSpecificationExecutor<Position> {

    fun findByIdAndProfileId(id: UUID, profileId: UUID): Position?

    fun findByDataSourceIdAndExternalId(dataSourceId: UUID, externalId: String): Position?

    fun findAllByProfileIdInOrderByClosedAtAsc(profileIds: Collection<UUID>): List<PnlPoint>

    fun countByDataSourceId(dataSourceId: UUID): Long
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
}
