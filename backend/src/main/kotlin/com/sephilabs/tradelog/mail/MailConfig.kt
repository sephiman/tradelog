// SPDX-License-Identifier: AGPL-3.0-only
package com.sephilabs.tradelog.mail

import com.sephilabs.tradelog.config.AppProperties
import org.slf4j.LoggerFactory
import org.slf4j.MDC
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import org.springframework.context.annotation.Profile
import org.springframework.core.task.TaskDecorator
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor
import java.util.concurrent.Executor
import java.util.concurrent.ThreadPoolExecutor

@Configuration
class MailConfig {

    private val log = LoggerFactory.getLogger(MailConfig::class.java)

    @Bean
    fun smtpSettings(props: AppProperties): SmtpSettings {
        val settings = SmtpSettings.resolve(props.mail, props.publicUrl)
        when (settings) {
            is SmtpSettings.Configured ->
                log.info("smtp_configured host={} port={} starttls={}", settings.host, settings.port, settings.startTls)
            is SmtpSettings.PartiallyConfigured ->
                log.warn(
                    "smtp_partially_configured missing={} outcome=mail_features_hidden",
                    settings.missing.joinToString(","),
                )
            SmtpSettings.Absent -> log.info("smtp_not_configured outcome=mail_features_hidden")
        }
        return settings
    }

    @Bean
    fun emailSender(settings: SmtpSettings): EmailSender = when (settings) {
        is SmtpSettings.Configured -> SmtpEmailSender(settings)
        else -> DisabledEmailSender
    }

    /** The user lookup and the SMTP round-trip run here so a reset request answers in constant time
     *  whether or not the address is known. Tests bind a blocking executor of the same name. */
    @Bean("mailExecutor")
    @Profile("!test")
    fun mailExecutor(): Executor = ThreadPoolTaskExecutor().apply {
        corePoolSize = 1
        maxPoolSize = 2
        queueCapacity = 100
        setThreadNamePrefix("mail-")
        setTaskDecorator(MdcPropagatingTaskDecorator)
        setRejectedExecutionHandler(ThreadPoolExecutor.CallerRunsPolicy())
        initialize()
    }
}

/** Carries the request's MDC (requestId, clientIp) onto the mail worker so every mail log line stays
 *  correlated with the request that caused it. */
object MdcPropagatingTaskDecorator : TaskDecorator {
    override fun decorate(runnable: Runnable): Runnable {
        val context = MDC.getCopyOfContextMap()
        return Runnable {
            if (context != null) MDC.setContextMap(context) else MDC.clear()
            try {
                runnable.run()
            } finally {
                MDC.clear()
            }
        }
    }
}
