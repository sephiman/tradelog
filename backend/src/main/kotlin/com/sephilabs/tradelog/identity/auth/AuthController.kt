// SPDX-License-Identifier: AGPL-3.0-only
package com.sephilabs.tradelog.identity.auth

import com.sephilabs.tradelog.common.errors.AppException
import com.sephilabs.tradelog.identity.user.User
import com.sephilabs.tradelog.identity.user.UserRepository
import com.sephilabs.tradelog.observability.AppMetrics
import com.sephilabs.tradelog.sync.LoginSyncTrigger
import jakarta.servlet.http.HttpServletRequest
import jakarta.servlet.http.HttpServletResponse
import jakarta.validation.Valid
import org.springframework.http.ResponseEntity
import org.springframework.security.authentication.AuthenticationManager
import org.springframework.security.authentication.BadCredentialsException
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken
import org.springframework.security.core.context.SecurityContextHolder
import org.springframework.security.web.context.HttpSessionSecurityContextRepository
import org.springframework.security.web.csrf.CsrfToken
import org.springframework.web.bind.annotation.*

@RestController
@RequestMapping("/api/auth")
class AuthController(
    private val users: UserRepository,
    private val authManager: AuthenticationManager,
    private val authService: AuthService,
    private val currentUser: CurrentUser,
    private val metrics: AppMetrics,
    private val rateLimiter: LoginRateLimiter,
    private val loginSyncTrigger: LoginSyncTrigger,
) {
    private val contextRepo = HttpSessionSecurityContextRepository()

    @GetMapping("/csrf")
    fun csrf(token: CsrfToken): Map<String, String> {
        // Touching the CsrfToken attribute forces Spring Security to materialize it and write
        // the XSRF-TOKEN cookie — the SPA needs this before any POST.
        return mapOf("headerName" to token.headerName, "parameterName" to token.parameterName)
    }

    @PostMapping("/login")
    fun login(
        @Valid @RequestBody body: LoginRequest,
        request: HttpServletRequest,
        response: HttpServletResponse,
    ): ResponseEntity<MeResponse> {
        val ip = request.remoteAddr ?: "unknown"
        if (!rateLimiter.tryAcquire("login:$ip")) {
            metrics.loginAttempt("rate_limited")
            throw AppException.tooManyRequests()
        }
        try {
            establishSession(body.email, body.password, request, response)
            val user = users.findByEmailIgnoreCase(body.email) ?: throw BadCredentialsException("INVALID_CREDENTIALS")
            // Authentication succeeded — clear this IP's failure budget so earlier mistyped
            // attempts don't keep counting against a legitimate user.
            rateLimiter.reset("login:$ip")
            authService.recordLogin(user.id)
            metrics.loginAttempt("success")
            // Fire-and-forget incremental sync of the user's active API data sources.
            loginSyncTrigger.onLogin(user.id)
            return ResponseEntity.ok(buildMe(user))
        } catch (ex: BadCredentialsException) {
            metrics.loginAttempt("failure")
            throw ex
        }
    }

    /** Authenticates the credentials and persists the SecurityContext into the (JDBC-backed) session. */
    private fun establishSession(email: String, password: String, request: HttpServletRequest, response: HttpServletResponse) {
        val auth = authManager.authenticate(UsernamePasswordAuthenticationToken(email, password))
        // Rotate the session id across the privilege change (session-fixation protection). With
        // formLogin disabled, Spring Security's own SessionFixationProtectionStrategy never runs.
        request.getSession(false)?.let { request.changeSessionId() }
        val context = SecurityContextHolder.createEmptyContext().apply { authentication = auth }
        SecurityContextHolder.setContext(context)
        contextRepo.saveContext(context, request, response)
    }

    @PostMapping("/logout")
    fun logout(request: HttpServletRequest): Map<String, String> {
        request.getSession(false)?.invalidate()
        SecurityContextHolder.clearContext()
        return mapOf("status" to "ok")
    }

    @PostMapping("/register")
    fun register(
        @Valid @RequestBody body: RegisterRequest,
        request: HttpServletRequest,
        response: HttpServletResponse,
    ): ResponseEntity<MeResponse> {
        // Same per-IP budget as login: registration is unauthenticated, runs Argon2, and creates
        // rows — without a limiter it is a spam/CPU amplifier.
        val ip = request.remoteAddr ?: "unknown"
        if (!rateLimiter.tryAcquire("register:$ip")) throw AppException.tooManyRequests()
        val user = authService.register(body)
        // Log the new user straight in, so the SPA's authenticated state is backed by a real session
        // (otherwise the first write after registering 401s until an explicit login).
        establishSession(body.email, body.password, request, response)
        return ResponseEntity.status(201).body(buildMe(user))
    }

    @GetMapping("/me")
    fun me(): MeResponse = buildMe(currentUser.requireUser())

    @PatchMapping("/me")
    fun updateMe(@Valid @RequestBody body: MeUpdateRequest): MeResponse {
        val current = currentUser.requireUser()
        return buildMe(authService.updateProfile(current.id, body.locale, body.timeZone))
    }

    @PostMapping("/password")
    fun changePassword(@Valid @RequestBody body: PasswordChangeRequest): Map<String, String> {
        val current = currentUser.requireUser()
        authService.changePassword(current.id, body.currentPassword, body.newPassword)
        return mapOf("status" to "ok")
    }

    private fun buildMe(user: User): MeResponse = MeResponse(user.id, user.email, user.locale, user.timeZone)
}
