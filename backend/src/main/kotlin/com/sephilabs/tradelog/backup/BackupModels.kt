// SPDX-License-Identifier: AGPL-3.0-only
package com.sephilabs.tradelog.backup

import com.sephilabs.tradelog.datasource.DataSourceStatus
import com.sephilabs.tradelog.datasource.SourceKind
import com.sephilabs.tradelog.position.FillAction
import com.sephilabs.tradelog.position.FillSide
import com.sephilabs.tradelog.position.PositionSide
import com.sephilabs.tradelog.profile.ProfileKind
import java.math.BigDecimal
import java.time.Instant

/**
 * The current version of the export envelope shape. The importer refuses any file whose
 * [BackupMeta.exportVersion] exceeds this, so a file written by a newer app is never silently
 * mis-read. [schemaVersion] is informational (the latest applied Flyway migration at export time).
 */
const val BACKUP_EXPORT_VERSION = 1
const val BACKUP_SCHEMA_VERSION = "V012"

/**
 * A portable, self-contained snapshot of everything one user owns: their taxonomy, profiles, data
 * sources (WITHOUT secrets), and the full position history with fills and tag annotations.
 *
 * Identity is structural, not by UUID — nothing references a database id. Tags are referenced by
 * (groupCode, tagCode) so the importer can re-resolve them against the freshly created taxonomy.
 * Encrypted API credentials are deliberately excluded; the sync cursor IS kept so that, once the
 * user re-enters their API keys after a restore, incremental sync resumes from the watermark
 * instead of re-pulling all history.
 */
data class BackupEnvelope(
    val meta: BackupMeta,
    val user: BackupUser,
    val taxonomy: BackupTaxonomy,
    val profiles: List<BackupProfile>,
)

data class BackupMeta(
    val exportVersion: Int,
    val schemaVersion: String,
    val exportedAt: Instant,
)

data class BackupUser(
    val email: String,
    val locale: String,
    val timeZone: String,
)

data class BackupTaxonomy(
    val groups: List<BackupTagGroup>,
)

data class BackupTagGroup(
    val code: String,
    val name: String,
    val sortOrder: Int,
    val tags: List<BackupTag>,
)

data class BackupTag(
    val code: String,
    val name: String,
    val sortOrder: Int,
)

data class BackupProfile(
    val name: String,
    val kind: ProfileKind,
    val strategyNote: String?,
    val dataSources: List<BackupDataSource>,
)

data class BackupDataSource(
    val kind: SourceKind,
    val label: String,
    val status: DataSourceStatus,
    /** JSON-encoded sync watermark; preserved so re-keyed API sources resume incremental sync. */
    val cursor: String?,
    val lastSyncedAt: Instant?,
    /** Immutable backfill floor chosen at creation; preserved so a restore doesn't re-pull older history. */
    val syncFrom: Instant? = null,
    val positions: List<BackupPosition>,
)

data class BackupPosition(
    val externalId: String,
    val source: SourceKind,
    val exchange: String?,
    val symbolBase: String,
    val symbolQuote: String,
    val side: PositionSide,
    val openedAt: Instant,
    val closedAt: Instant,
    val qty: BigDecimal,
    val entryPrice: BigDecimal,
    val exitPrice: BigDecimal,
    val realizedPnl: BigDecimal,
    val netPnl: BigDecimal,
    val fees: BigDecimal,
    val funding: BigDecimal,
    val pnlCurrency: String,
    val note: String?,
    val raw: String?,
    /** Soft-delete marker; carried so a deletion survives a backup → restore round-trip. Null = live. */
    val deletedAt: Instant? = null,
    val fills: List<BackupFill>,
    val tags: List<BackupTagRef>,
)

data class BackupFill(
    val seq: Int,
    val action: FillAction,
    val side: FillSide,
    val ts: Instant,
    val price: BigDecimal,
    val qty: BigDecimal,
    val value: BigDecimal?,
    val fee: BigDecimal?,
)

/** A position's tag, referenced by the codes of its group and tag rather than database ids. */
data class BackupTagRef(
    val groupCode: String,
    val tagCode: String,
)

/** What a restore did, surfaced to the user after import. */
data class ImportSummary(
    val profiles: Int,
    val dataSources: Int,
    val positions: Int,
    val fills: Int,
    val tags: Int,
)
