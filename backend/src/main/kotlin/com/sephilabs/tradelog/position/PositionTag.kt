// SPDX-License-Identifier: AGPL-3.0-only
package com.sephilabs.tradelog.position

import jakarta.persistence.Column
import jakarta.persistence.Embeddable
import jakarta.persistence.EmbeddedId
import jakarta.persistence.Entity
import jakarta.persistence.Table
import java.io.Serializable
import java.util.UUID

@Embeddable
data class PositionTagId(
    @Column(name = "position_id") var positionId: UUID = UUID(0, 0),
    @Column(name = "group_id") var groupId: UUID = UUID(0, 0),
) : Serializable

/**
 * Link of a position to one tag within a group. The (position_id, group_id) primary key allows
 * at most one tag per group per position, so each annotation dimension is single-select.
 */
@Entity
@Table(name = "position_tags")
class PositionTag(
    @EmbeddedId
    var id: PositionTagId = PositionTagId(),

    @Column(name = "tag_id", nullable = false)
    var tagId: UUID,
)
