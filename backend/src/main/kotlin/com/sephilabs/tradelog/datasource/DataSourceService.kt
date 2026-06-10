// SPDX-License-Identifier: AGPL-3.0-only
package com.sephilabs.tradelog.datasource

import com.fasterxml.jackson.databind.ObjectMapper
import com.fasterxml.jackson.module.kotlin.readValue
import com.sephilabs.tradelog.common.crypto.CredentialCrypto
import com.sephilabs.tradelog.common.errors.AppException
import com.sephilabs.tradelog.connector.ExchangeCredentials
import com.sephilabs.tradelog.connector.SyncCursor
import com.sephilabs.tradelog.position.PositionRepository
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import java.util.UUID

@Service
class DataSourceService(
    private val dataSources: DataSourceRepository,
    private val positions: PositionRepository,
    private val crypto: CredentialCrypto,
    private val objectMapper: ObjectMapper,
) {

    @Transactional(readOnly = true)
    fun list(profileId: UUID): List<DataSourceDto> =
        dataSources.findAllByProfileIdOrderByCreatedAtAsc(profileId).map { it.toDto() }

    @Transactional(readOnly = true)
    fun get(profileId: UUID, id: UUID): DataSourceDto = loadOwn(profileId, id).toDto()

    @Transactional
    fun create(profileId: UUID, request: CreateDataSourceRequest): DataSourceDto {
        val ds = DataSource(
            profileId = profileId,
            kind = request.kind,
            label = request.label.trim(),
        )
        if (request.kind.isApi) {
            ds.credentialsEnc = encryptCredentials(request.apiKey, request.apiSecret, request.passphrase)
            ds.syncFrom = request.syncFrom
        }
        dataSources.save(ds)
        return ds.toDto()
    }

    @Transactional
    fun update(profileId: UUID, id: UUID, request: UpdateDataSourceRequest): DataSourceDto {
        val ds = loadOwn(profileId, id)
        request.label?.trim()?.takeIf { it.isNotEmpty() }?.let { ds.label = it }
        request.status?.let {
            ds.status = it
            if (it != DataSourceStatus.ERROR) ds.statusDetail = null
        }
        if (ds.kind.isApi && !request.apiKey.isNullOrBlank() && !request.apiSecret.isNullOrBlank()) {
            ds.credentialsEnc = encryptCredentials(request.apiKey, request.apiSecret, request.passphrase)
            // Rotating credentials clears a prior error state so the next sync can re-validate.
            if (ds.status == DataSourceStatus.ERROR) {
                ds.status = DataSourceStatus.ACTIVE
                ds.statusDetail = null
            }
        }
        return ds.toDto()
    }

    @Transactional
    fun delete(profileId: UUID, id: UUID) {
        dataSources.delete(loadOwn(profileId, id))
    }

    // --- Internal helpers used by the sync worker (credentials decrypted only here) ---

    fun credentialsOf(ds: DataSource): ExchangeCredentials {
        val enc = ds.credentialsEnc ?: throw AppException.badRequest("DATA_SOURCE_NO_CREDENTIALS")
        val stored = objectMapper.readValue<DataSourceCredentials>(crypto.decrypt(enc))
        return ExchangeCredentials(stored.apiKey, stored.apiSecret, stored.passphrase)
    }

    fun cursorOf(ds: DataSource): SyncCursor =
        ds.cursor?.let { objectMapper.readValue<SyncCursor>(it) } ?: SyncCursor()

    fun writeCursor(ds: DataSource, cursor: SyncCursor) {
        ds.cursor = objectMapper.writeValueAsString(cursor)
    }

    private fun loadOwn(profileId: UUID, id: UUID): DataSource =
        dataSources.findByIdAndProfileId(id, profileId) ?: throw AppException.notFound("DATA_SOURCE_NOT_FOUND")

    private fun encryptCredentials(apiKey: String?, apiSecret: String?, passphrase: String?): String {
        if (apiKey.isNullOrBlank() || apiSecret.isNullOrBlank()) {
            throw AppException.badRequest("DATA_SOURCE_CREDENTIALS_REQUIRED")
        }
        val json = objectMapper.writeValueAsString(DataSourceCredentials(apiKey.trim(), apiSecret.trim(), passphrase?.trim()?.takeIf { it.isNotEmpty() }))
        return crypto.encrypt(json)
    }

    private fun DataSource.toDto() = DataSourceDto(
        id = id,
        kind = kind,
        label = label,
        status = status,
        statusDetail = statusDetail,
        hasCredentials = credentialsEnc != null,
        lastSyncedAt = lastSyncedAt,
        syncFrom = syncFrom,
        positionCount = positions.countByDataSourceIdAndDeletedAtIsNull(id),
        createdAt = createdAt,
    )
}
