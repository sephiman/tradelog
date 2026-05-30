// SPDX-License-Identifier: AGPL-3.0-only
package com.sephilabs.tradelog

import org.springframework.boot.autoconfigure.SpringBootApplication
import org.springframework.boot.context.properties.ConfigurationPropertiesScan
import org.springframework.boot.runApplication
import org.springframework.scheduling.annotation.EnableScheduling

@SpringBootApplication
@ConfigurationPropertiesScan
@EnableScheduling
class TradelogApplication

fun main(args: Array<String>) {
    runApplication<TradelogApplication>(*args)
}
