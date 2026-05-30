// SPDX-License-Identifier: AGPL-3.0-only
package com.sephilabs.tradelog.profile

import com.sephilabs.tradelog.common.TimestampedEntity
import jakarta.persistence.Column
import jakarta.persistence.Entity
import jakarta.persistence.EnumType
import jakarta.persistence.Enumerated
import jakarta.persistence.Id
import jakarta.persistence.Table
import java.util.UUID

enum class ProfileKind { PERSONAL, BOT }

@Entity
@Table(name = "profiles")
class Profile(
    @Id
    @Column(name = "id", nullable = false, updatable = false)
    var id: UUID = UUID.randomUUID(),

    @Column(name = "user_id", nullable = false, updatable = false)
    var userId: UUID,

    @Enumerated(EnumType.STRING)
    @Column(name = "kind", nullable = false, length = 16)
    var kind: ProfileKind,

    @Column(name = "name", nullable = false, length = 80)
    var name: String,

    /** Free-text strategy note, used mainly for BOT profiles. */
    @Column(name = "strategy_note", length = 1000)
    var strategyNote: String? = null,
) : TimestampedEntity()
