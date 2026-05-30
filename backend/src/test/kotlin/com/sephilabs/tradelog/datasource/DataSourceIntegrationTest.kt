// SPDX-License-Identifier: AGPL-3.0-only
package com.sephilabs.tradelog.datasource

import com.sephilabs.tradelog.IntegrationTestBase
import com.sephilabs.tradelog.common.errors.AppException
import com.sephilabs.tradelog.connector.SyncCursor
import com.sephilabs.tradelog.identity.user.User
import com.sephilabs.tradelog.identity.user.UserRepository
import com.sephilabs.tradelog.profile.Profile
import com.sephilabs.tradelog.profile.ProfileKind
import com.sephilabs.tradelog.profile.ProfileRepository
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import java.time.Instant
import java.util.UUID

class DataSourceIntegrationTest @Autowired constructor(
    private val service: DataSourceService,
    private val dataSources: DataSourceRepository,
    private val profiles: ProfileRepository,
    private val users: UserRepository,
) : IntegrationTestBase() {

    private fun newProfile(): UUID {
        val u = users.save(User(email = "ds${System.nanoTime()}@example.com", passwordHash = "x"))
        return profiles.save(Profile(userId = u.id, kind = ProfileKind.PERSONAL, name = "P${System.nanoTime()}")).id
    }

    @Test
    fun `API credentials are encrypted at rest, never plaintext, and decrypt back`() {
        val profileId = newProfile()
        val apiKey = "BITUNIX-KEY-${UUID.randomUUID()}"
        val apiSecret = "BITUNIX-SECRET-${UUID.randomUUID()}"

        val dto = service.create(profileId, CreateDataSourceRequest(SourceKind.BITUNIX, "main", apiKey, apiSecret))
        assertThat(dto.hasCredentials).isTrue
        assertThat(dto.status).isEqualTo(DataSourceStatus.ACTIVE)

        val entity = dataSources.findById(dto.id).orElseThrow()
        assertThat(entity.credentialsEnc).isNotNull()
        // Ciphertext must not contain the plaintext key or secret.
        assertThat(entity.credentialsEnc!!).doesNotContain(apiKey).doesNotContain(apiSecret)

        val decrypted = service.credentialsOf(entity)
        assertThat(decrypted.apiKey).isEqualTo(apiKey)
        assertThat(decrypted.apiSecret).isEqualTo(apiSecret)
    }

    @Test
    fun `Quantfury source has no credentials`() {
        val dto = service.create(newProfile(), CreateDataSourceRequest(SourceKind.QUANTFURY, "pdf"))
        assertThat(dto.hasCredentials).isFalse
        assertThat(dataSources.findById(dto.id).orElseThrow().credentialsEnc).isNull()
    }

    @Test
    fun `API source without credentials is rejected`() {
        assertThatThrownBy { service.create(newProfile(), CreateDataSourceRequest(SourceKind.BINGX, "x")) }
            .isInstanceOf(AppException::class.java)
            .hasMessageContaining("DATA_SOURCE_CREDENTIALS_REQUIRED")
    }

    @Test
    fun `sync cursor round-trips through JSON`() {
        val dto = service.create(newProfile(), CreateDataSourceRequest(SourceKind.BITUNIX, "c", "k", "s"))
        val entity = dataSources.findById(dto.id).orElseThrow()
        val ts = Instant.parse("2026-01-02T03:04:05Z")
        service.writeCursor(entity, SyncCursor(lastClosedAt = ts, lastExternalId = "ext-9"))
        dataSources.save(entity)

        val cursor = service.cursorOf(dataSources.findById(dto.id).orElseThrow())
        assertThat(cursor.lastClosedAt).isEqualTo(ts)
        assertThat(cursor.lastExternalId).isEqualTo("ext-9")
    }
}
