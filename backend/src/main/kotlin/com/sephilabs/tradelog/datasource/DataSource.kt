// SPDX-License-Identifier: AGPL-3.0-only
package com.sephilabs.tradelog.datasource

import com.sephilabs.tradelog.common.TimestampedEntity
import jakarta.persistence.Column
import jakarta.persistence.Entity
import jakarta.persistence.EnumType
import jakarta.persistence.Enumerated
import jakarta.persistence.Id
import jakarta.persistence.Table
import org.springframework.data.jpa.repository.JpaRepository
import java.time.Instant
import java.util.UUID

enum class DataSourceStatus { ACTIVE, ERROR, DISABLED }

/**
 * A per-profile connector instance. API sources hold AES-GCM-encrypted credentials and a JSON sync
 * cursor; the Quantfury PDF source carries neither. [credentialsEnc] is never exposed through the API.
 */
@Entity
@Table(name = "data_sources")
class DataSource(
    @Id
    @Column(name = "id", nullable = false, updatable = false)
    var id: UUID = UUID.randomUUID(),

    @Column(name = "profile_id", nullable = false, updatable = false)
    var profileId: UUID,

    @Enumerated(EnumType.STRING)
    @Column(name = "kind", nullable = false, length = 16, updatable = false)
    var kind: SourceKind,

    @Column(name = "label", nullable = false, length = 80)
    var label: String,

    @Enumerated(EnumType.STRING)
    @Column(name = "status", nullable = false, length = 16)
    var status: DataSourceStatus = DataSourceStatus.ACTIVE,

    /** i18n code describing the current status (e.g. an error reason); never free-form user text. */
    @Column(name = "status_detail", length = 64)
    var statusDetail: String? = null,

    /** AES-GCM ciphertext of the API credentials JSON. Null for credential-less sources. */
    @Column(name = "credentials_enc")
    var credentialsEnc: String? = null,

    /** JSON-encoded [com.sephilabs.tradelog.connector.SyncCursor]. */
    @Column(name = "cursor")
    var cursor: String? = null,

    @Column(name = "last_synced_at")
    var lastSyncedAt: Instant? = null,
) : TimestampedEntity()

interface DataSourceRepository : JpaRepository<DataSource, UUID> {
    fun findAllByProfileIdOrderByCreatedAtAsc(profileId: UUID): List<DataSource>
    fun findByIdAndProfileId(id: UUID, profileId: UUID): DataSource?
    fun findAllByProfileIdInAndStatus(profileIds: Collection<UUID>, status: DataSourceStatus): List<DataSource>
    fun findAllByStatusAndKindIn(status: DataSourceStatus, kinds: Collection<SourceKind>): List<DataSource>
}
