// SPDX-License-Identifier: AGPL-3.0-only
package com.sephilabs.tradelog.aianalysis

import com.sephilabs.tradelog.config.AppProperties
import org.slf4j.LoggerFactory
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import org.springframework.context.annotation.Profile
import org.springframework.http.client.BufferingClientHttpRequestFactory
import org.springframework.http.client.JdkClientHttpRequestFactory
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor
import org.springframework.web.client.RestClient
import java.net.http.HttpClient
import java.time.Duration
import java.util.concurrent.Executor
import java.util.concurrent.ThreadPoolExecutor

@Configuration
class AiConfig {

    private val log = LoggerFactory.getLogger(AiConfig::class.java)

    @Bean
    fun aiProviderSettings(props: AppProperties): AiProviderSettings {
        val settings = AiProviderSettings.resolve(props.ai)
        when (settings) {
            is AiProviderSettings.Configured ->
                log.info("ai_configured host={} model={}", settings.host, settings.model)
            is AiProviderSettings.PartiallyConfigured ->
                log.warn(
                    "ai_partially_configured missing={} outcome=ai_analysis_hidden",
                    settings.missing.joinToString(","),
                )
            AiProviderSettings.Absent -> log.info("ai_not_configured outcome=ai_analysis_hidden")
        }
        return settings
    }

    @Bean
    fun aiChatClient(settings: AiProviderSettings, restClientBuilder: RestClient.Builder): AiChatClient {
        if (settings !is AiProviderSettings.Configured) return UnconfiguredAiChatClient
        val timeout = Duration.ofMillis(settings.timeoutMs)
        val jdk = JdkClientHttpRequestFactory(HttpClient.newBuilder().connectTimeout(timeout).build())
            .apply { setReadTimeout(timeout) }
        // Buffered so the request carries Content-Length: streamed, the JDK client sends it chunked, which
        // not every OpenAI-compatible server accepts.
        val requestFactory = BufferingClientHttpRequestFactory(jdk)
        return OpenAiCompatibleChatClient(settings, restClientBuilder.requestFactory(requestFactory))
    }

    /** One analysis at a time per profile is not enforced (a run is cheap); this only bounds the
     *  instance-wide number of concurrent provider conversations. Tests supply a synchronous executor. */
    @Bean("aiAnalysisExecutor")
    @Profile("!test")
    fun aiAnalysisExecutor(): Executor = ThreadPoolTaskExecutor().apply {
        corePoolSize = 1
        maxPoolSize = 2
        queueCapacity = 50
        setThreadNamePrefix("ai-analysis-")
        setRejectedExecutionHandler(ThreadPoolExecutor.CallerRunsPolicy())
        initialize()
    }
}
