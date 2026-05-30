// SPDX-License-Identifier: AGPL-3.0-only
package com.sephilabs.tradelog.profile

import com.sephilabs.tradelog.common.errors.AppException
import jakarta.servlet.http.HttpServletRequest
import org.springframework.stereotype.Component
import java.util.UUID

/** Resolves the profile id validated by [ProfileAccessInterceptor] for the current request. */
@Component
class ProfileContext(private val request: HttpServletRequest) {

    fun profileId(): UUID =
        request.getAttribute(ATTR_PROFILE_ID) as? UUID
            ?: throw AppException.forbidden("NOT_PROFILE_OWNER")
}
