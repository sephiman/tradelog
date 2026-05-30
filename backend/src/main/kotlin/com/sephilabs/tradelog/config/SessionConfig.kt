// SPDX-License-Identifier: AGPL-3.0-only
package com.sephilabs.tradelog.config

import org.springframework.context.annotation.Configuration
import org.springframework.session.FlushMode
import org.springframework.session.jdbc.config.annotation.web.http.EnableJdbcHttpSession

/**
 * Persist HTTP sessions in PostgreSQL via Spring Session JDBC so they survive backend restarts and
 * work across instances. Explicitly enabled because the classpath auto-configuration was not
 * engaging under Spring Boot 4 — without this, sessions live only in Tomcat memory and every
 * restart/deploy logs everyone out (first request after a restart 401s, re-login "fixes" it).
 *
 * [FlushMode.IMMEDIATE] writes the session to the database as soon as it is created/modified
 * (i.e. during the login request) instead of at request commit. Without it there is a read-after-
 * write race: the SPA fires its first requests in the milliseconds after login returns, before the
 * default end-of-request save is visible, so they 401 and only the retry (~1s later) succeeds.
 *
 * Table is the SPRING_SESSION schema created by Flyway V001; 30-day inactive interval matches the
 * session cookie max-age.
 */
@Configuration
@EnableJdbcHttpSession(
    maxInactiveIntervalInSeconds = 2_592_000,
    tableName = "SPRING_SESSION",
    flushMode = FlushMode.IMMEDIATE,
)
class SessionConfig
