// SPDX-License-Identifier: AGPL-3.0-only
package com.sephilabs.tradelog.position

import com.sephilabs.tradelog.datasource.SourceKind
import org.springframework.data.jpa.domain.Specification
import java.time.Instant
import java.util.UUID

data class PositionSearchCriteria(
    val profileId: UUID,
    val symbolBase: String? = null,
    val side: PositionSide? = null,
    val source: SourceKind? = null,
    val exchange: String? = null,
    val from: Instant? = null,
    val to: Instant? = null,
    val tagId: UUID? = null,
    /** When set, keep only positions that have NO tag in this group (e.g. "origen unset"). */
    val untaggedGroupId: UUID? = null,
    val page: Int = 0,
    val size: Int = 50,
    val sort: String = "closed_desc",
)

object PositionSpecs {
    fun fromCriteria(c: PositionSearchCriteria): Specification<Position> =
        Specification<Position> { root, query, cb ->
            val predicates = mutableListOf(
                cb.equal(root.get<UUID>("profileId"), c.profileId),
                cb.isNull(root.get<Instant>("deletedAt")),
            )
            c.symbolBase?.takeIf { it.isNotBlank() }?.let {
                predicates += cb.equal(cb.upper(root.get("symbolBase")), it.uppercase())
            }
            c.side?.let { predicates += cb.equal(root.get<PositionSide>("side"), it) }
            c.source?.let { predicates += cb.equal(root.get<SourceKind>("source"), it) }
            c.exchange?.takeIf { it.isNotBlank() }?.let { predicates += cb.equal(root.get<String>("exchange"), it) }
            c.from?.let { predicates += cb.greaterThanOrEqualTo(root.get("closedAt"), it) }
            c.to?.let { predicates += cb.lessThanOrEqualTo(root.get("closedAt"), it) }
            c.tagId?.let { tagId ->
                // EXISTS (SELECT 1 FROM position_tags pt WHERE pt.position_id = root.id AND pt.tag_id = :tagId)
                val sub = query!!.subquery(UUID::class.java)
                val pt = sub.from(PositionTag::class.java)
                sub.select(pt.get<PositionTagId>("id").get("positionId"))
                    .where(
                        cb.equal(pt.get<PositionTagId>("id").get<UUID>("positionId"), root.get<UUID>("id")),
                        cb.equal(pt.get<UUID>("tagId"), tagId),
                    )
                predicates += cb.exists(sub)
            }
            c.untaggedGroupId?.let { groupId ->
                // NOT EXISTS (SELECT 1 FROM position_tags pt WHERE pt.position_id = root.id AND pt.group_id = :groupId)
                val sub = query!!.subquery(UUID::class.java)
                val pt = sub.from(PositionTag::class.java)
                sub.select(pt.get<PositionTagId>("id").get("positionId"))
                    .where(
                        cb.equal(pt.get<PositionTagId>("id").get<UUID>("positionId"), root.get<UUID>("id")),
                        cb.equal(pt.get<PositionTagId>("id").get<UUID>("groupId"), groupId),
                    )
                predicates += cb.not(cb.exists(sub))
            }
            cb.and(*predicates.toTypedArray())
        }

    /** Positions in [profileId] whose id is one of [ids] — drops any id from another profile. */
    fun byIds(profileId: UUID, ids: Collection<UUID>): Specification<Position> =
        Specification<Position> { root, _, cb ->
            cb.and(
                cb.equal(root.get<UUID>("profileId"), profileId),
                cb.isNull(root.get<Instant>("deletedAt")),
                root.get<UUID>("id").`in`(ids),
            )
        }
}
