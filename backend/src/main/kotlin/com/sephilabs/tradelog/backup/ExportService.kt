// SPDX-License-Identifier: AGPL-3.0-only
package com.sephilabs.tradelog.backup

import com.sephilabs.tradelog.datasource.DataSourceRepository
import com.sephilabs.tradelog.identity.user.User
import com.sephilabs.tradelog.position.Position
import com.sephilabs.tradelog.position.PositionFillRepository
import com.sephilabs.tradelog.position.PositionRepository
import com.sephilabs.tradelog.position.PositionTagRepository
import com.sephilabs.tradelog.taxonomy.TagGroupRepository
import com.sephilabs.tradelog.taxonomy.TagRepository
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import java.time.Instant
import java.util.UUID

/**
 * Assembles a [BackupEnvelope] for a single user. Read-only: it never touches credentials (those are
 * intentionally omitted) and produces a fully structural snapshot keyed by codes, not database ids.
 */
@Service
class ExportService(
    private val profiles: com.sephilabs.tradelog.profile.ProfileRepository,
    private val dataSources: DataSourceRepository,
    private val positions: PositionRepository,
    private val fills: PositionFillRepository,
    private val positionTags: PositionTagRepository,
    private val tagGroups: TagGroupRepository,
    private val tags: TagRepository,
) {

    @Transactional(readOnly = true)
    fun export(user: User): BackupEnvelope {
        // Resolve the user's taxonomy once, and a tagId -> (groupCode, tagCode) lookup for positions.
        val groups = tagGroups.findAllByUserIdOrderBySortOrderAscNameAsc(user.id)
        val groupCodeById = groups.associate { it.id to it.code }
        val tagsByGroup = tags.findAllByGroupIdInOrderBySortOrderAscNameAsc(groups.map { it.id })
            .groupBy { it.groupId }
        val tagRefById: Map<UUID, BackupTagRef> = tagsByGroup.values.flatten().associate { tag ->
            tag.id to BackupTagRef(groupCode = groupCodeById.getValue(tag.groupId), tagCode = tag.code)
        }

        val taxonomy = BackupTaxonomy(
            groups = groups.map { g ->
                BackupTagGroup(
                    code = g.code,
                    name = g.name,
                    sortOrder = g.sortOrder,
                    tags = (tagsByGroup[g.id] ?: emptyList()).map { BackupTag(it.code, it.name, it.sortOrder) },
                )
            },
        )

        val backupProfiles = profiles.findAllByUserIdOrderByCreatedAtAsc(user.id).map { profile ->
            BackupProfile(
                name = profile.name,
                kind = profile.kind,
                strategyNote = profile.strategyNote,
                dataSources = dataSources.findAllByProfileIdOrderByCreatedAtAsc(profile.id).map { ds ->
                    val rows = positions.findAllByDataSourceIdOrderByClosedAtAsc(ds.id)
                    val positionIds = rows.map { it.id }
                    val fillsByPosition = fills.findAllByPositionIdIn(positionIds).groupBy { it.positionId }
                    val tagsByPosition = positionTags.findAllByIdPositionIdIn(positionIds)
                        .groupBy { it.id.positionId }
                    BackupDataSource(
                        kind = ds.kind,
                        label = ds.label,
                        status = ds.status,
                        cursor = ds.cursor,
                        lastSyncedAt = ds.lastSyncedAt,
                        syncFrom = ds.syncFrom,
                        positions = rows.map { p ->
                            toBackupPosition(p, fillsByPosition[p.id].orEmpty(), tagsByPosition[p.id].orEmpty(), tagRefById)
                        },
                    )
                },
            )
        }

        return BackupEnvelope(
            meta = BackupMeta(BACKUP_EXPORT_VERSION, BACKUP_SCHEMA_VERSION, Instant.now()),
            user = BackupUser(email = user.email, locale = user.locale, timeZone = user.timeZone),
            taxonomy = taxonomy,
            profiles = backupProfiles,
        )
    }

    private fun toBackupPosition(
        p: Position,
        fills: List<com.sephilabs.tradelog.position.PositionFill>,
        tags: List<com.sephilabs.tradelog.position.PositionTag>,
        tagRefById: Map<UUID, BackupTagRef>,
    ) = BackupPosition(
        externalId = p.externalId,
        source = p.source,
        exchange = p.exchange,
        symbolBase = p.symbolBase,
        symbolQuote = p.symbolQuote,
        side = p.side,
        openedAt = p.openedAt,
        closedAt = p.closedAt,
        qty = p.qty,
        entryPrice = p.entryPrice,
        exitPrice = p.exitPrice,
        realizedPnl = p.realizedPnl,
        netPnl = p.netPnl,
        fees = p.fees,
        funding = p.funding,
        pnlCurrency = p.pnlCurrency,
        note = p.note,
        raw = p.raw,
        fills = fills.sortedBy { it.seq }.map {
            BackupFill(it.seq, it.action, it.side, it.ts, it.price, it.qty, it.value, it.fee)
        },
        // A tag whose row somehow lost its taxonomy entry is dropped rather than exported dangling.
        tags = tags.mapNotNull { tagRefById[it.tagId] },
    )
}
