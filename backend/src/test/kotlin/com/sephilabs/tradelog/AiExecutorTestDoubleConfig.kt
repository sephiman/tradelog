// SPDX-License-Identifier: AGPL-3.0-only
package com.sephilabs.tradelog

import org.springframework.boot.test.context.TestConfiguration
import org.springframework.context.annotation.Bean
import java.util.concurrent.Executor

/** Replaces the production `aiAnalysisExecutor` (excluded from the test profile): runs the provider
 *  conversation synchronously, so endpoint tests observe the finished run without polling. */
@TestConfiguration
class AiExecutorTestDoubleConfig {

    @Bean("aiAnalysisExecutor")
    fun aiAnalysisExecutor(): Executor = Executor { it.run() }
}
