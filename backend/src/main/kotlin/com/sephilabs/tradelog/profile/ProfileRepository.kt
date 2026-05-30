// SPDX-License-Identifier: AGPL-3.0-only
package com.sephilabs.tradelog.profile

import org.springframework.data.jpa.repository.JpaRepository
import java.util.UUID

interface ProfileRepository : JpaRepository<Profile, UUID> {
    fun findByIdAndUserId(id: UUID, userId: UUID): Profile?
    fun findAllByUserIdOrderByCreatedAtAsc(userId: UUID): List<Profile>
    fun existsByUserIdAndNameIgnoreCase(userId: UUID, name: String): Boolean
}
