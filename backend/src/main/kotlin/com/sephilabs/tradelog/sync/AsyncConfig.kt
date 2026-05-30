// SPDX-License-Identifier: AGPL-3.0-only
package com.sephilabs.tradelog.sync

import com.sephilabs.tradelog.config.AppProperties
import org.slf4j.LoggerFactory
import org.springframework.aop.interceptor.AsyncUncaughtExceptionHandler
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import org.springframework.scheduling.annotation.AsyncConfigurer
import org.springframework.scheduling.annotation.EnableAsync
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor
import java.util.concurrent.Executor
import java.util.concurrent.ThreadPoolExecutor

/**
 * Bounded executor for the on-login sync kickoff so a backlog or a slow exchange can never exhaust
 * threads or block request handling. Overflow falls back to running on the caller (CallerRunsPolicy).
 */
@Configuration
@EnableAsync
class AsyncConfig(private val props: AppProperties) : AsyncConfigurer {

    @Bean("syncExecutor")
    fun syncExecutor(): Executor = ThreadPoolTaskExecutor().apply {
        corePoolSize = props.sync.executor.corePoolSize
        maxPoolSize = props.sync.executor.maxPoolSize
        queueCapacity = props.sync.executor.queueCapacity
        setThreadNamePrefix("sync-")
        setRejectedExecutionHandler(ThreadPoolExecutor.CallerRunsPolicy())
        initialize()
    }

    override fun getAsyncUncaughtExceptionHandler(): AsyncUncaughtExceptionHandler =
        AsyncUncaughtExceptionHandler { ex, method, _ ->
            LoggerFactory.getLogger(AsyncConfig::class.java).error("Uncaught async error in {}", method.name, ex)
        }
}
