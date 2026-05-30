// SPDX-License-Identifier: AGPL-3.0-only
package com.sephilabs.tradelog.taxonomy

import com.sephilabs.tradelog.common.errors.AppException
import org.springframework.dao.DataIntegrityViolationException
import org.springframework.stereotype.Component
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Propagation
import org.springframework.transaction.annotation.Transactional
import java.util.UUID

@Service
class TaxonomyService(
    private val groups: TagGroupRepository,
    private val tags: TagRepository,
    private val seeder: TaxonomySeeder,
) {

    /**
     * Creates the default per-user taxonomy the first time it is needed. Idempotent and safe under
     * concurrency: if two first-time requests race, one wins and the other's duplicate insert is
     * swallowed (the seed runs in its own transaction so the loser doesn't poison the caller's).
     */
    fun ensureSeeded(userId: UUID) {
        if (groups.existsByUserId(userId)) return
        try {
            seeder.seedDefaults(userId)
        } catch (_: DataIntegrityViolationException) {
            // A concurrent request seeded it first — the data now exists, nothing to do.
        }
    }

    @Transactional
    fun listGroups(userId: UUID): List<TagGroupDto> {
        ensureSeeded(userId)
        val gs = groups.findAllByUserIdOrderBySortOrderAscNameAsc(userId)
        if (gs.isEmpty()) return emptyList()
        val byGroup = tags.findAllByGroupIdInOrderBySortOrderAscNameAsc(gs.map { it.id }).groupBy { it.groupId }
        return gs.map { g ->
            TagGroupDto(g.id, g.code, g.name, g.sortOrder, (byGroup[g.id] ?: emptyList()).map { it.toDto() })
        }
    }

    @Transactional
    fun createGroup(userId: UUID, request: TagGroupRequest): TagGroupDto {
        val name = request.name.trim()
        val group = TagGroup(userId = userId, code = uniqueGroupCode(userId, name), name = name)
        groups.save(group)
        return TagGroupDto(group.id, group.code, group.name, group.sortOrder, emptyList())
    }

    @Transactional
    fun updateGroup(userId: UUID, groupId: UUID, request: TagGroupRequest): TagGroupDto {
        val group = ownGroup(userId, groupId)
        group.name = request.name.trim()
        val tagList = tags.findAllByGroupIdInOrderBySortOrderAscNameAsc(listOf(group.id)).map { it.toDto() }
        return TagGroupDto(group.id, group.code, group.name, group.sortOrder, tagList)
    }

    @Transactional
    fun deleteGroup(userId: UUID, groupId: UUID) {
        groups.delete(ownGroup(userId, groupId))
    }

    @Transactional
    fun createTag(userId: UUID, groupId: UUID, request: TagRequest): TagDto {
        val group = ownGroup(userId, groupId)
        val name = request.name.trim()
        val tag = Tag(groupId = group.id, code = uniqueTagCode(group.id, name), name = name)
        tags.save(tag)
        return tag.toDto()
    }

    @Transactional
    fun updateTag(userId: UUID, groupId: UUID, tagId: UUID, request: TagRequest): TagDto {
        ownGroup(userId, groupId)
        val tag = tags.findByIdAndGroupId(tagId, groupId) ?: throw AppException.notFound("TAG_NOT_FOUND")
        tag.name = request.name.trim()
        return tag.toDto()
    }

    @Transactional
    fun deleteTag(userId: UUID, groupId: UUID, tagId: UUID) {
        ownGroup(userId, groupId)
        val tag = tags.findByIdAndGroupId(tagId, groupId) ?: throw AppException.notFound("TAG_NOT_FOUND")
        tags.delete(tag)
    }

    /** Returns the group id of [tagId] if it belongs to a group owned by [userId], else null. */
    @Transactional(readOnly = true)
    fun groupIdOfOwnedTag(userId: UUID, tagId: UUID): UUID? {
        val tag = tags.findById(tagId).orElse(null) ?: return null
        val group = groups.findByIdAndUserId(tag.groupId, userId) ?: return null
        return group.id
    }

    private fun ownGroup(userId: UUID, groupId: UUID): TagGroup =
        groups.findByIdAndUserId(groupId, userId) ?: throw AppException.notFound("TAG_GROUP_NOT_FOUND")

    private fun uniqueGroupCode(userId: UUID, name: String): String {
        val base = slug(name).take(28).ifEmpty { "group" }
        var candidate = base
        var n = 2
        while (groups.existsByUserIdAndCode(userId, candidate)) {
            candidate = "${base.take(26)}-$n"; n++
        }
        return candidate
    }

    private fun uniqueTagCode(groupId: UUID, name: String): String {
        val base = slug(name).take(44).ifEmpty { "tag" }
        var candidate = base
        var n = 2
        while (tags.existsByGroupIdAndCode(groupId, candidate)) {
            candidate = "${base.take(42)}-$n"; n++
        }
        return candidate
    }

}

/**
 * Inserts the default per-user taxonomy in its OWN transaction (REQUIRES_NEW). Kept separate so a
 * concurrent-seed unique-constraint violation rolls back only this insert, not the caller's
 * transaction (and so the proxy boundary actually applies the new propagation).
 */
@Component
class TaxonomySeeder(
    private val groups: TagGroupRepository,
    private val tags: TagRepository,
) {
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    fun seedDefaults(userId: UUID) {
        val origen = groups.save(TagGroup(userId = userId, code = "origen", name = "Origen", sortOrder = 10))
        SEED_TAGS.forEachIndexed { i, name ->
            tags.save(Tag(groupId = origen.id, code = slug(name), name = name, sortOrder = (i + 1) * 10))
        }
    }

    private companion object {
        val SEED_TAGS = listOf("Discrecional", "Señal", "Copy")
    }
}

private fun Tag.toDto() = TagDto(id, code, name, sortOrder)

/** Lowercase ASCII slug: keeps a–z/0–9, turns runs of anything else into single hyphens. */
internal fun slug(input: String): String =
    input.trim().lowercase()
        .map { if (it in 'a'..'z' || it in '0'..'9') it else '-' }
        .joinToString("")
        .replace(Regex("-+"), "-")
        .trim('-')
