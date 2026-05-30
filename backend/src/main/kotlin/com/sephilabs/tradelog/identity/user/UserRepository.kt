// SPDX-License-Identifier: AGPL-3.0-only
package com.sephilabs.tradelog.identity.user

import org.springframework.data.jpa.repository.JpaRepository
import java.util.UUID

interface UserRepository : JpaRepository<User, UUID> {

    fun findByEmailIgnoreCase(email: String): User?

    fun existsByEmailIgnoreCase(email: String): Boolean
}
