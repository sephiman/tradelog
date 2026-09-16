// SPDX-License-Identifier: AGPL-3.0-only
package com.sephilabs.tradelog.identity.auth

import jakarta.servlet.http.HttpServletRequest
import jakarta.servlet.http.HttpServletResponse
import org.springframework.security.authentication.AuthenticationManager
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken
import org.springframework.security.core.context.SecurityContextHolder
import org.springframework.security.web.context.HttpSessionSecurityContextRepository
import org.springframework.session.FindByIndexNameSessionRepository
import org.springframework.session.Session
import org.springframework.stereotype.Component

/**
 * The server-side session rows (SPRING_SESSION) of one user. Every session is indexed by its
 * principal name — the login email — which is why an email change has to rewrite them rather than
 * leave them pointing at an address the user no longer has.
 */
@Component
class UserSessions(
    // The Spring Session API rather than the JDBC implementation: the concrete repository's
    // session type is not public, so its inferred return type leaks out of scope.
    private val sessions: FindByIndexNameSessionRepository<out Session>,
    private val authManager: AuthenticationManager,
) {
    private val contextRepo = HttpSessionSecurityContextRepository()

    /** Authenticates the credentials and persists the SecurityContext into the JDBC-backed session. */
    fun establish(email: String, password: String, request: HttpServletRequest, response: HttpServletResponse) {
        val auth = authManager.authenticate(UsernamePasswordAuthenticationToken(email, password))
        // Rotate the session id across the privilege change (session-fixation protection). With
        // formLogin disabled, Spring Security's own SessionFixationProtectionStrategy never runs.
        request.getSession(false)?.let { request.changeSessionId() }
        val context = SecurityContextHolder.createEmptyContext().apply { authentication = auth }
        SecurityContextHolder.setContext(context)
        contextRepo.saveContext(context, request, response)
    }

    /** Deletes every server-side session of [principalName] (the login email). */
    fun invalidateAll(principalName: String) {
        sessions.findByPrincipalName(principalName).keys.forEach(sessions::deleteById)
    }
}
