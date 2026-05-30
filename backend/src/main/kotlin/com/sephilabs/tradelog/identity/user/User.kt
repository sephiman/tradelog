// SPDX-License-Identifier: AGPL-3.0-only
package com.sephilabs.tradelog.identity.user

import com.sephilabs.tradelog.common.TimestampedEntity
import jakarta.persistence.Column
import jakarta.persistence.Entity
import jakarta.persistence.Id
import jakarta.persistence.Table
import java.time.Instant
import java.util.UUID

@Entity
@Table(name = "users")
class User(
    @Id
    @Column(name = "id", nullable = false, updatable = false)
    var id: UUID = UUID.randomUUID(),

    @Column(name = "email", nullable = false, unique = true)
    var email: String,

    @Column(name = "password_hash", nullable = false)
    var passwordHash: String,

    @Column(name = "locale", nullable = false, length = 2)
    var locale: String = "en",

    @Column(name = "last_login_at")
    var lastLoginAt: Instant? = null,
) : TimestampedEntity()
