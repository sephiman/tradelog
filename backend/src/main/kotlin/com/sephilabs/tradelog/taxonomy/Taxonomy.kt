// SPDX-License-Identifier: AGPL-3.0-only
package com.sephilabs.tradelog.taxonomy

import com.sephilabs.tradelog.common.TimestampedEntity
import jakarta.persistence.Column
import jakarta.persistence.Entity
import jakarta.persistence.Id
import jakarta.persistence.Table
import org.springframework.data.jpa.repository.JpaRepository
import java.util.UUID

@Entity
@Table(name = "tag_groups")
class TagGroup(
    @Id
    @Column(name = "id", nullable = false, updatable = false)
    var id: UUID = UUID.randomUUID(),

    @Column(name = "user_id", nullable = false, updatable = false)
    var userId: UUID,

    @Column(name = "code", nullable = false, length = 32)
    var code: String,

    @Column(name = "name", nullable = false, length = 80)
    var name: String,

    @Column(name = "sort_order", nullable = false)
    var sortOrder: Int = 1000,
) : TimestampedEntity()

@Entity
@Table(name = "tags")
class Tag(
    @Id
    @Column(name = "id", nullable = false, updatable = false)
    var id: UUID = UUID.randomUUID(),

    @Column(name = "group_id", nullable = false, updatable = false)
    var groupId: UUID,

    @Column(name = "code", nullable = false, length = 48)
    var code: String,

    @Column(name = "name", nullable = false, length = 80)
    var name: String,

    @Column(name = "sort_order", nullable = false)
    var sortOrder: Int = 1000,
) : TimestampedEntity()

interface TagGroupRepository : JpaRepository<TagGroup, UUID> {
    fun findAllByUserIdOrderBySortOrderAscNameAsc(userId: UUID): List<TagGroup>
    fun findByIdAndUserId(id: UUID, userId: UUID): TagGroup?
    fun existsByUserId(userId: UUID): Boolean
    fun existsByUserIdAndCode(userId: UUID, code: String): Boolean
}

interface TagRepository : JpaRepository<Tag, UUID> {
    fun findAllByGroupIdInOrderBySortOrderAscNameAsc(groupIds: Collection<UUID>): List<Tag>
    fun findByIdAndGroupId(id: UUID, groupId: UUID): Tag?
    fun existsByGroupIdAndCode(groupId: UUID, code: String): Boolean
}
