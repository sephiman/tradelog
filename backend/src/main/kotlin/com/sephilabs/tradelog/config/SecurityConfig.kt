// SPDX-License-Identifier: AGPL-3.0-only
package com.sephilabs.tradelog.config

import com.sephilabs.tradelog.identity.auth.AppUserDetailsService
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import org.springframework.http.HttpStatus
import org.springframework.security.authentication.AuthenticationManager
import org.springframework.security.authentication.ProviderManager
import org.springframework.security.authentication.dao.DaoAuthenticationProvider
import org.springframework.security.config.annotation.web.builders.HttpSecurity
import org.springframework.security.crypto.argon2.Argon2PasswordEncoder
import org.springframework.security.crypto.password.PasswordEncoder
import org.springframework.security.web.SecurityFilterChain
import org.springframework.security.web.authentication.HttpStatusEntryPoint
import org.springframework.security.web.csrf.CookieCsrfTokenRepository
import org.springframework.security.web.csrf.CsrfTokenRequestAttributeHandler

@Configuration
class SecurityConfig {

    @Bean
    fun passwordEncoder(): PasswordEncoder =
        Argon2PasswordEncoder.defaultsForSpringSecurity_v5_8()

    @Bean
    fun authenticationManager(userDetailsService: AppUserDetailsService, encoder: PasswordEncoder): AuthenticationManager {
        val provider = DaoAuthenticationProvider(userDetailsService).apply {
            setPasswordEncoder(encoder)
        }
        return ProviderManager(provider)
    }

    @Bean
    fun securityFilterChain(http: HttpSecurity): SecurityFilterChain {
        http
            .securityMatcher("/**")
            .csrf { csrf ->
                csrf.csrfTokenRepository(CookieCsrfTokenRepository.withHttpOnlyFalse())
                // Plain (non-XOR) handler — the SPA reads the XSRF-TOKEN cookie and forwards the
                // raw value as X-XSRF-TOKEN. The default XOR handler would reject a raw token.
                csrf.csrfTokenRequestHandler(CsrfTokenRequestAttributeHandler())
                csrf.ignoringRequestMatchers("/actuator/**")
            }
            .authorizeHttpRequests { auth ->
                auth.requestMatchers(
                    "/api/auth/csrf",
                    "/api/auth/login",
                    "/api/auth/register",
                ).permitAll()
                auth.requestMatchers("/actuator/**").permitAll()
                auth.requestMatchers("/api/**").authenticated()
                auth.anyRequest().denyAll()
            }
            .exceptionHandling { eh ->
                eh.authenticationEntryPoint(HttpStatusEntryPoint(HttpStatus.UNAUTHORIZED))
            }
            .formLogin { it.disable() }
            .httpBasic { it.disable() }
            .logout { it.disable() }

        return http.build()
    }
}
