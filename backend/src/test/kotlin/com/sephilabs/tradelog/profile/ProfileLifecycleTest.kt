// SPDX-License-Identifier: AGPL-3.0-only
package com.sephilabs.tradelog.profile

import com.sephilabs.tradelog.IntegrationTestBase
import com.sephilabs.tradelog.common.errors.AppException
import com.sephilabs.tradelog.identity.user.User
import com.sephilabs.tradelog.identity.user.UserRepository
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import java.util.UUID

class ProfileLifecycleTest @Autowired constructor(
    private val service: ProfileService,
    private val profiles: ProfileRepository,
    private val users: UserRepository,
) : IntegrationTestBase() {

    private fun newUser(): UUID =
        users.save(User(email = "p${System.nanoTime()}@example.com", passwordHash = "x")).id

    @Test
    fun `create trims name, lists, gets, updates and deletes`() {
        val u = newUser()
        val created = service.create(u, ProfileRequest(ProfileKind.BOT, "  Scalper  ", "grid v1"))
        assertThat(created.name).isEqualTo("Scalper")
        assertThat(created.kind).isEqualTo(ProfileKind.BOT)
        assertThat(created.strategyNote).isEqualTo("grid v1")

        assertThat(service.list(u).map { it.id }).contains(created.id)
        assertThat(service.get(u, created.id).name).isEqualTo("Scalper")

        val updated = service.update(u, created.id, ProfileRequest(ProfileKind.PERSONAL, "Manual", null))
        assertThat(updated.kind).isEqualTo(ProfileKind.PERSONAL)
        assertThat(updated.name).isEqualTo("Manual")
        assertThat(updated.strategyNote).isNull()

        service.delete(u, created.id)
        assertThat(profiles.findByIdAndUserId(created.id, u)).isNull()
    }

    @Test
    fun `name must be unique per user (case-insensitive)`() {
        val u = newUser()
        service.create(u, ProfileRequest(ProfileKind.PERSONAL, "Main", null))
        assertThatThrownBy { service.create(u, ProfileRequest(ProfileKind.PERSONAL, "  main ", null)) }
            .isInstanceOf(AppException::class.java)
            .hasMessageContaining("PROFILE_NAME_TAKEN")
    }

    @Test
    fun `profiles are private — another user cannot read or mutate them`() {
        val owner = newUser()
        val other = newUser()
        val p = service.create(owner, ProfileRequest(ProfileKind.PERSONAL, "Owned", null))

        assertThat(service.list(other)).noneMatch { it.id == p.id }
        assertThatThrownBy { service.get(other, p.id) }.hasMessageContaining("PROFILE_NOT_FOUND")
        assertThatThrownBy { service.update(other, p.id, ProfileRequest(ProfileKind.BOT, "X", null)) }
            .hasMessageContaining("PROFILE_NOT_FOUND")
        assertThatThrownBy { service.delete(other, p.id) }.hasMessageContaining("PROFILE_NOT_FOUND")
    }
}
