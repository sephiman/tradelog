// SPDX-License-Identifier: AGPL-3.0-only
package com.sephilabs.tradelog.position

import com.sephilabs.tradelog.common.PageResponse
import com.sephilabs.tradelog.common.errors.AppException
import com.sephilabs.tradelog.taxonomy.TagGroupRepository
import com.sephilabs.tradelog.taxonomy.TagRepository
import com.sephilabs.tradelog.taxonomy.TaxonomyService
import org.springframework.data.domain.PageRequest
import org.springframework.data.domain.Sort
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import java.util.UUID

@Service
class PositionService(
    private val positions: PositionRepository,
    private val fills: PositionFillRepository,
    private val positionTags: PositionTagRepository,
    private val tagGroups: TagGroupRepository,
    private val tags: TagRepository,
    private val taxonomy: TaxonomyService,
) {

    @Transactional(readOnly = true)
    fun search(criteria: PositionSearchCriteria): PageResponse<PositionDto> {
        val sort = when (criteria.sort) {
            "closed_asc" -> Sort.by(Sort.Order.asc("closedAt"))
            "opened_desc" -> Sort.by(Sort.Order.desc("openedAt"))
            "pnl_desc" -> Sort.by(Sort.Order.desc("netPnl"), Sort.Order.desc("closedAt"))
            "pnl_asc" -> Sort.by(Sort.Order.asc("netPnl"), Sort.Order.desc("closedAt"))
            else -> Sort.by(Sort.Order.desc("closedAt"))
        }
        val pageable = PageRequest.of(criteria.page, criteria.size.coerceIn(1, 200), sort)
        val page = positions.findAll(PositionSpecs.fromCriteria(criteria), pageable)
        val ids = page.content.map { it.id }
        val tagViews = tagViewsByPosition(ids)
        val fillCounts = fillCountsByPosition(ids)
        val items = page.content.map { it.toDto(tagViews[it.id] ?: emptyList(), fillCounts[it.id] ?: 0) }
        return PageResponse.of(items, criteria.page, criteria.size, page.totalElements)
    }

    @Transactional(readOnly = true)
    fun exchanges(profileId: UUID): List<String> = positions.findDistinctExchanges(profileId)

    /** All closed positions for the profile as lightweight rows for the analytics dashboard. */
    @Transactional(readOnly = true)
    fun closedSummary(profileId: UUID): List<ClosedPositionSummaryDto> {
        val rows = positions.findAllByProfileIdOrderByClosedAtAsc(profileId)
        val tagViews = tagViewsByPosition(rows.map { it.id })
        return rows.map {
            ClosedPositionSummaryDto(
                id = it.id,
                source = it.source,
                exchange = it.exchange,
                symbolBase = it.symbolBase,
                symbolQuote = it.symbolQuote,
                side = it.side,
                openedAt = it.openedAt,
                closedAt = it.closedAt,
                realizedPnl = it.realizedPnl,
                netPnl = it.netPnl,
                fees = it.fees,
                funding = it.funding,
                tags = tagViews[it.id] ?: emptyList(),
            )
        }
    }

    @Transactional(readOnly = true)
    fun get(profileId: UUID, positionId: UUID): PositionDetailDto {
        val position = loadOwn(profileId, positionId)
        val tagViews = tagViewsByPosition(listOf(position.id))[position.id] ?: emptyList()
        val fillList = fills.findAllByPositionIdOrderBySeqAsc(position.id)
        return PositionDetailDto(position.toDto(tagViews, fillList.size), fillList.map(PositionFillDto::of))
    }

    @Transactional
    fun updateNote(profileId: UUID, positionId: UUID, note: String?) {
        val position = loadOwn(profileId, positionId)
        position.note = note?.trim()?.takeIf { it.isNotEmpty() }
    }

    @Transactional
    fun setTag(userId: UUID, profileId: UUID, positionId: UUID, groupId: UUID, tagId: UUID) {
        loadOwn(profileId, positionId)
        val owningGroup = taxonomy.groupIdOfOwnedTag(userId, tagId)
            ?: throw AppException.notFound("TAG_NOT_FOUND")
        if (owningGroup != groupId) throw AppException.badRequest("TAG_GROUP_MISMATCH")
        val existing = positionTags.findByIdPositionIdAndIdGroupId(positionId, groupId)
        if (existing != null) {
            existing.tagId = tagId
        } else {
            positionTags.save(PositionTag(PositionTagId(positionId, groupId), tagId))
        }
    }

    @Transactional
    fun clearTag(profileId: UUID, positionId: UUID, groupId: UUID) {
        loadOwn(profileId, positionId)
        positionTags.deleteByIdPositionIdAndIdGroupId(positionId, groupId)
    }

    /** Set (tagId != null) or clear (tagId == null) one tag group on many positions; returns how many were touched. */
    @Transactional
    fun bulkSetTag(userId: UUID, profileId: UUID, groupId: UUID, req: BulkSetTagRequest): Int {
        if (req.tagId != null) {
            val owningGroup = taxonomy.groupIdOfOwnedTag(userId, req.tagId)
                ?: throw AppException.notFound("TAG_NOT_FOUND")
            if (owningGroup != groupId) throw AppException.badRequest("TAG_GROUP_MISMATCH")
        }
        val ids = resolveBulkTargetIds(profileId, req)
        if (ids.isEmpty()) return 0
        positionTags.deleteByIdGroupIdAndIdPositionIdIn(groupId, ids)
        if (req.tagId != null) {
            positionTags.saveAll(ids.map { PositionTag(PositionTagId(it, groupId), req.tagId) })
        }
        return ids.size
    }

    private fun resolveBulkTargetIds(profileId: UUID, req: BulkSetTagRequest): List<UUID> {
        val explicit = req.positionIds
        val spec = if (!explicit.isNullOrEmpty()) {
            PositionSpecs.byIds(profileId, explicit)
        } else {
            val f = req.filters ?: BulkTagFilters()
            PositionSpecs.fromCriteria(
                PositionSearchCriteria(
                    profileId, f.symbol, f.side, f.source, f.exchange, f.from, f.to, f.tagId, f.untaggedGroupId
                )
            )
        }
        return positions.findAll(spec).map { it.id }
    }

    private fun loadOwn(profileId: UUID, positionId: UUID): Position =
        positions.findByIdAndProfileId(positionId, profileId) ?: throw AppException.notFound("POSITION_NOT_FOUND")

    private fun fillCountsByPosition(positionIds: List<UUID>): Map<UUID, Int> {
        if (positionIds.isEmpty()) return emptyMap()
        return fills.findAllByPositionIdIn(positionIds).groupingBy { it.positionId }.eachCount()
    }

    private fun tagViewsByPosition(positionIds: List<UUID>): Map<UUID, List<PositionTagView>> {
        if (positionIds.isEmpty()) return emptyMap()
        val links = positionTags.findAllByIdPositionIdIn(positionIds)
        if (links.isEmpty()) return emptyMap()
        val tagsById = tags.findAllById(links.map { it.tagId }).associateBy { it.id }
        val groupsById = tagGroups.findAllById(links.map { it.id.groupId }).associateBy { it.id }
        return links.mapNotNull { link ->
            val tag = tagsById[link.tagId] ?: return@mapNotNull null
            val group = groupsById[link.id.groupId] ?: return@mapNotNull null
            link.id.positionId to PositionTagView(group.id, group.code, group.name, tag.id, tag.name)
        }.groupBy({ it.first }, { it.second })
    }
}

private fun Position.toDto(tags: List<PositionTagView>, fillCount: Int) = PositionDto(
    id = id,
    source = source,
    exchange = exchange,
    symbolBase = symbolBase,
    symbolQuote = symbolQuote,
    side = side,
    openedAt = openedAt,
    closedAt = closedAt,
    qty = qty,
    entryPrice = entryPrice,
    exitPrice = exitPrice,
    realizedPnl = realizedPnl,
    netPnl = netPnl,
    fees = fees,
    funding = funding,
    pnlCurrency = pnlCurrency,
    note = note,
    tags = tags,
    fillCount = fillCount,
)
