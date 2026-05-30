// SPDX-License-Identifier: AGPL-3.0-only
package com.sephilabs.tradelog.taxonomy

import com.sephilabs.tradelog.IntegrationTestBase
import com.sephilabs.tradelog.identity.user.User
import com.sephilabs.tradelog.identity.user.UserRepository
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import java.util.UUID

class TaxonomyIntegrationTest @Autowired constructor(
    private val service: TaxonomyService,
    private val users: UserRepository,
) : IntegrationTestBase() {

    private fun newUser(): UUID =
        users.save(User(email = "tax${System.nanoTime()}@example.com", passwordHash = "x")).id

    @Test
    fun `first read seeds the Origen group and is idempotent`() {
        val u = newUser()
        val first = service.listGroups(u)
        assertThat(first).hasSize(1)
        assertThat(first[0].code).isEqualTo("origen")
        assertThat(first[0].tags.map { it.name }).contains("Discrecional", "Señal", "Copy")

        // Calling again must not create duplicate groups.
        val second = service.listGroups(u)
        assertThat(second).hasSize(1)
        assertThat(second[0].id).isEqualTo(first[0].id)
    }

    @Test
    fun `tags can be created, renamed and deleted within a group`() {
        val u = newUser()
        val origen = service.listGroups(u).first { it.code == "origen" }

        val created = service.createTag(u, origen.id, TagRequest("Breakout"))
        assertThat(service.listGroups(u).first { it.id == origen.id }.tags.map { it.name }).contains("Breakout")

        service.updateTag(u, origen.id, created.id, TagRequest("Breakout v2"))
        service.deleteTag(u, origen.id, created.id)

        val names = service.listGroups(u).first { it.id == origen.id }.tags.map { it.name }
        assertThat(names).doesNotContain("Breakout", "Breakout v2")
    }

    @Test
    fun `groupIdOfOwnedTag enforces ownership`() {
        val owner = newUser()
        val other = newUser()
        val origen = service.listGroups(owner).first { it.code == "origen" }
        val tagId = origen.tags.first().id

        assertThat(service.groupIdOfOwnedTag(owner, tagId)).isEqualTo(origen.id)
        assertThat(service.groupIdOfOwnedTag(other, tagId)).isNull()
    }
}
