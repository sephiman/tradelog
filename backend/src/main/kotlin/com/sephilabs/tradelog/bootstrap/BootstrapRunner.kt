// SPDX-License-Identifier: AGPL-3.0-only
package com.sephilabs.tradelog.bootstrap

import com.sephilabs.tradelog.config.AppProperties
import com.sephilabs.tradelog.identity.user.User
import com.sephilabs.tradelog.identity.user.UserRepository
import org.slf4j.LoggerFactory
import org.springframework.boot.ApplicationRunner
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import org.springframework.security.crypto.password.PasswordEncoder
import org.springframework.transaction.PlatformTransactionManager
import org.springframework.transaction.support.TransactionTemplate

@Configuration
class BootstrapRunner(
    private val props: AppProperties,
    private val users: UserRepository,
    private val encoder: PasswordEncoder,
    private val txManager: PlatformTransactionManager,
) {

    private val log = LoggerFactory.getLogger(BootstrapRunner::class.java)

    @Bean
    fun bootstrap(): ApplicationRunner = ApplicationRunner {
        val tx = TransactionTemplate(txManager)
        tx.execute {
            if (users.count() > 0L) {
                log.info("Bootstrap skipped: users already exist")
                return@execute
            }
            val email = props.bootstrap.adminEmail
            val password = props.bootstrap.adminPassword
            if (email.isBlank() || password.isBlank()) {
                if (props.registration.mode == AppProperties.RegistrationMode.OPEN) {
                    log.warn("Bootstrap skipped: ADMIN_EMAIL/PASSWORD not set. First user must self-register (REGISTRATION_MODE=open).")
                    return@execute
                }
                error("ADMIN_EMAIL and ADMIN_PASSWORD are required for first-run bootstrap when REGISTRATION_MODE is not 'open'")
            }
            val admin = User(
                email = email.lowercase(),
                passwordHash = encoder.encode(password)!!,
            )
            users.save(admin)
            log.info("Bootstrap created admin user {}", admin.email)
        }
    }
}
