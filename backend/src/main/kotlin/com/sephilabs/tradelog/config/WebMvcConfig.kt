// SPDX-License-Identifier: AGPL-3.0-only
package com.sephilabs.tradelog.config

import com.sephilabs.tradelog.profile.ProfileAccessInterceptor
import org.springframework.context.annotation.Configuration
import org.springframework.web.servlet.config.annotation.InterceptorRegistry
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer

@Configuration
class WebMvcConfig(private val profileAccessInterceptor: ProfileAccessInterceptor) : WebMvcConfigurer {
    override fun addInterceptors(registry: InterceptorRegistry) {
        // The interceptor itself no-ops when the path has no {profileId} variable
        // (e.g. the list/create endpoints), so a broad pattern is safe.
        registry.addInterceptor(profileAccessInterceptor)
            .addPathPatterns("/api/profiles/**")
    }
}
