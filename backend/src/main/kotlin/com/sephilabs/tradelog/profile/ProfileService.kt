// SPDX-License-Identifier: AGPL-3.0-only
package com.sephilabs.tradelog.profile

import com.sephilabs.tradelog.common.errors.AppException
import com.sephilabs.tradelog.datasource.DataSourceRepository
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import java.util.UUID

@Service
class ProfileService(
    private val profiles: ProfileRepository,
    private val dataSources: DataSourceRepository,
) {

    @Transactional(readOnly = true)
    fun list(userId: UUID): List<ProfileDto> =
        profiles.findAllByUserIdOrderByCreatedAtAsc(userId).map(ProfileDto::of)

    @Transactional
    fun create(userId: UUID, request: ProfileRequest): ProfileDto {
        val name = request.name.trim()
        if (profiles.existsByUserIdAndNameIgnoreCase(userId, name)) {
            throw AppException.conflict("PROFILE_NAME_TAKEN")
        }
        val profile = Profile(
            userId = userId,
            kind = request.kind,
            name = name,
            strategyNote = request.strategyNote?.trim()?.takeIf { it.isNotEmpty() },
        )
        profiles.save(profile)
        return ProfileDto.of(profile)
    }

    @Transactional(readOnly = true)
    fun get(userId: UUID, id: UUID): ProfileDto = ProfileDto.of(loadOwn(userId, id))

    @Transactional
    fun update(userId: UUID, id: UUID, request: ProfileRequest): ProfileDto {
        val profile = loadOwn(userId, id)
        val name = request.name.trim()
        if (!name.equals(profile.name, ignoreCase = true) && profiles.existsByUserIdAndNameIgnoreCase(userId, name)) {
            throw AppException.conflict("PROFILE_NAME_TAKEN")
        }
        profile.kind = request.kind
        profile.name = name
        profile.strategyNote = request.strategyNote?.trim()?.takeIf { it.isNotEmpty() }
        return ProfileDto.of(profile)
    }

    @Transactional
    fun delete(userId: UUID, id: UUID) {
        val profile = loadOwn(userId, id)
        // Data sources reference this profile with ON DELETE RESTRICT; remove them first.
        // Their positions are removed by the DB's ON DELETE CASCADE on data_source_id.
        dataSources.deleteByProfileId(profile.id)
        profiles.delete(profile)
    }

    private fun loadOwn(userId: UUID, id: UUID): Profile =
        profiles.findByIdAndUserId(id, userId) ?: throw AppException.notFound("PROFILE_NOT_FOUND")
}
