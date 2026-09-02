// SPDX-License-Identifier: AGPL-3.0-only
package com.sephilabs.tradelog.sync

import com.sephilabs.tradelog.IntegrationTestBase
import com.sephilabs.tradelog.datasource.CreateDataSourceRequest
import com.sephilabs.tradelog.datasource.DataSourceRepository
import com.sephilabs.tradelog.datasource.DataSourceService
import com.sephilabs.tradelog.datasource.DataSourceStatus
import com.sephilabs.tradelog.datasource.SourceKind
import com.sephilabs.tradelog.identity.user.User
import com.sephilabs.tradelog.identity.user.UserRepository
import com.sephilabs.tradelog.profile.Profile
import com.sephilabs.tradelog.profile.ProfileKind
import com.sephilabs.tradelog.profile.ProfileRepository
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.boot.test.web.server.LocalServerPort
import org.springframework.boot.testcontainers.service.connection.ServiceConnection
import org.springframework.test.context.ActiveProfiles
import java.net.CookieManager
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpRequest.BodyPublishers
import java.net.http.HttpResponse.BodyHandlers
import java.util.UUID

/** What an outage does to a source. Bitunix points at a closed local port: refused like a dead network. */
@SpringBootTest(
    webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
    properties = ["app.connectors.bitunix.base-url=http://127.0.0.1:1"],
)
@ActiveProfiles("test")
class TransientOutageIntegrationTest @Autowired constructor(
    private val syncService: SyncService,
    private val dataSourceService: DataSourceService,
    private val dataSources: DataSourceRepository,
    private val profiles: ProfileRepository,
    private val users: UserRepository,
) {
    companion object {
        @JvmStatic
        @ServiceConnection
        val postgres = IntegrationTestBase.postgres

        val apiKinds = SourceKind.entries.filter { it.isApi }
    }

    @LocalServerPort
    var port: Int = 0

    private fun newProfile(): UUID {
        val u = users.save(User(email = "outage${System.nanoTime()}@example.com", passwordHash = "x"))
        return profiles.save(Profile(userId = u.id, kind = ProfileKind.PERSONAL, name = "P${System.nanoTime()}")).id
    }

    private fun bitunixSource(profileId: UUID): UUID =
        dataSourceService.create(profileId, CreateDataSourceRequest(SourceKind.BITUNIX, "bitunix", "k", "s")).id

    /** The sources the nightly sweep would pick up, straight from its own query. */
    private fun sweptSourceIds(): List<UUID> =
        dataSources.findAllByStatusNotAndKindIn(DataSourceStatus.DISABLED, apiKinds).map { it.id }

    @Test
    fun `an unreachable venue leaves the source untouched and still swept`() {
        val id = bitunixSource(newProfile())

        val run = syncService.syncApiSource(dataSources.findById(id).orElseThrow(), SyncTrigger.SCHEDULED)

        // The run records the outage...
        assertThat(run.status).isEqualTo(RunStatus.ERROR)
        assertThat(run.errorCode).isEqualTo(SyncStore.UNREACHABLE_CODE)
        // ...but nothing is wrong with the source, so it is not marked as if there were.
        val ds = dataSources.findById(id).orElseThrow()
        assertThat(ds.status).isEqualTo(DataSourceStatus.ACTIVE)
        assertThat(ds.statusDetail).isNull()
        assertThat(sweptSourceIds()).contains(id)
    }

    @Test
    fun `a source parked in ERROR is retried instead of being skipped forever`() {
        val profileId = newProfile()
        val id = bitunixSource(profileId)
        park(id)

        // Both sweeps see it: the nightly one and the on-login one.
        assertThat(sweptSourceIds()).contains(id)
        val onLogin = dataSources.findAllByProfileIdInAndStatusNot(listOf(profileId), DataSourceStatus.DISABLED)
        assertThat(onLogin.map { it.id }).contains(id)
    }

    @Test
    fun `sync all attempts an errored source rather than reporting nothing to do`() {
        val session = registerSession()
        val profileId = createProfile(session)
        val dataSourceId = createBitunixSource(session, profileId)
        park(UUID.fromString(dataSourceId))

        val response = session.post("/api/profiles/$profileId/sync", "")

        // An empty array here is what made the UI claim a sync was already in progress for hours.
        assertThat(response.statusCode()).isEqualTo(200)
        assertThat(response.body()).contains(dataSourceId)
        assertThat(response.body()).contains(SyncStore.UNREACHABLE_CODE)
    }

    /** Puts a source in ERROR, exactly where a failed sync used to leave it. */
    private fun park(id: UUID) {
        dataSources.save(
            dataSources.findById(id).orElseThrow().apply {
                status = DataSourceStatus.ERROR
                statusDetail = "SYNC_FAILED"
            },
        )
    }

    // --- HTTP, so the endpoint the sync button calls is the one under test ---

    private fun url(p: String) = URI.create("http://localhost:$port$p")

    private inner class Session {
        val cm = CookieManager()
        val client: HttpClient = HttpClient.newBuilder().cookieHandler(cm).build()
        fun xsrf() = cm.cookieStore.cookies.first { it.name == "XSRF-TOKEN" }.value

        fun post(path: String, body: String) = client.send(
            HttpRequest.newBuilder(url(path))
                .header("Content-Type", "application/json").header("X-XSRF-TOKEN", xsrf())
                .POST(BodyPublishers.ofString(body)).build(),
            BodyHandlers.ofString(),
        )
    }

    private fun registerSession(): Session {
        val s = Session()
        s.client.send(HttpRequest.newBuilder(url("/api/auth/csrf")).GET().build(), BodyHandlers.discarding())
        val email = "outage${System.nanoTime()}@example.com"
        val r = s.post("/api/auth/register", """{"email":"$email","password":"password123","locale":"en"}""")
        assertThat(r.statusCode()).isEqualTo(201)
        return s
    }

    private fun createProfile(s: Session): String {
        val r = s.post("/api/profiles", """{"kind":"PERSONAL","name":"P${System.nanoTime()}"}""")
        assertThat(r.statusCode()).isEqualTo(201)
        return idOf(r.body())
    }

    private fun createBitunixSource(s: Session, profileId: String): String {
        val r = s.post(
            "/api/profiles/$profileId/data-sources",
            """{"kind":"BITUNIX","label":"bitunix","apiKey":"k","apiSecret":"s"}""",
        )
        assertThat(r.statusCode()).isEqualTo(201)
        return idOf(r.body())
    }

    private fun idOf(body: String) = Regex(""""id"\s*:\s*"([^"]+)"""").find(body)!!.groupValues[1]
}
