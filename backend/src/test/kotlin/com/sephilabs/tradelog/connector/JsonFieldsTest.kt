// SPDX-License-Identifier: AGPL-3.0-only
package com.sephilabs.tradelog.connector

import com.fasterxml.jackson.databind.ObjectMapper
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import java.math.BigDecimal
import java.time.Instant

/** The payload readers every connector maps through: string numbers, renamed fields, odd timestamps. */
class JsonFieldsTest {

    private val mapper = ObjectMapper()
    private fun node(json: String) = mapper.readTree(json)

    @Test
    fun `numbers are read whether sent as JSON numbers or as strings`() {
        val n = node("""{ "a": 12.5, "b": "12.5", "c": 7, "d": "7" }""")
        assertThat(n.dec("a")).isEqualByComparingTo("12.5")
        assertThat(n.dec("b")).isEqualByComparingTo("12.5")
        assertThat(n.long("c")).isEqualTo(7L)
        assertThat(n.long("d")).isEqualTo(7L)
        assertThat(n.int("c")).isEqualTo(7)
        assertThat(n.int("d")).isEqualTo(7)
    }

    @Test
    fun `the first present candidate wins, so a renamed field still maps`() {
        val renamed = node("""{ "realised_profit": "3.5" }""")
        assertThat(renamed.dec(listOf("realized_profit", "realised_profit", "profit"))).isEqualByComparingTo("3.5")
        // Order matters: the venue's current name is listed first and takes precedence.
        val both = node("""{ "realized_profit": "1", "realised_profit": "2" }""")
        assertThat(both.dec(listOf("realized_profit", "realised_profit"))).isEqualByComparingTo("1")
    }

    @Test
    fun `absent, null, blank and unparseable values read as null rather than as zero`() {
        val n = node("""{ "nothing": null, "blank": "  ", "words": "n/a", "obj": {}, "arr": [] }""")
        assertThat(n.dec("missing")).isNull()
        assertThat(n.dec("nothing")).isNull()
        assertThat(n.dec("blank")).isNull()
        assertThat(n.dec("words")).isNull()
        // A container is not a value: reading it as a number must not yield a bogus figure.
        assertThat(n.dec("obj")).isNull()
        assertThat(n.dec("arr")).isNull()
        assertThat(n.text("nothing")).isNull()
        assertThat(n.long("words")).isNull()
    }

    @Test
    fun `zero is a real value and must not be confused with absent`() {
        val n = node("""{ "fee": "0", "pnl": 0 }""")
        assertThat(n.dec("fee")).isEqualByComparingTo(BigDecimal.ZERO)
        assertThat(n.dec("pnl")).isEqualByComparingTo(BigDecimal.ZERO)
    }

    @Test
    fun `timestamps parse from seconds, milliseconds, numeric strings and ISO-8601`() {
        val millis = 1_700_000_000_000L
        val seconds = millis / 1000
        assertThat(node("""{ "t": $millis }""").instant("t")).isEqualTo(Instant.ofEpochMilli(millis))
        assertThat(node("""{ "t": $seconds }""").instant("t")).isEqualTo(Instant.ofEpochSecond(seconds))
        assertThat(node("""{ "t": "$millis" }""").instant("t")).isEqualTo(Instant.ofEpochMilli(millis))
        assertThat(node("""{ "t": "2023-11-14T22:13:20Z" }""").instant("t"))
            .isEqualTo(Instant.parse("2023-11-14T22:13:20Z"))
        assertThat(node("""{ "t": "2023-11-14T23:13:20+01:00" }""").instant("t"))
            .isEqualTo(Instant.parse("2023-11-14T22:13:20Z"))
    }

    @Test
    fun `an unparseable timestamp is null, never a default instant`() {
        // Skipping the row is correct; dating it to the epoch would put a real trade in 1970 and
        // quietly corrupt every period-based analytic.
        assertThat(node("""{ "t": "yesterday" }""").instant("t")).isNull()
        assertThat(node("""{ "t": null }""").instant("t")).isNull()
        assertThat(node("{}").instant("t")).isNull()
    }

    @Test
    fun `a later candidate is used when the earlier one is present but empty`() {
        val n = node("""{ "filledTm": "", "time": 1700000000000 }""")
        assertThat(n.instant(listOf("filledTm", "time"))).isEqualTo(Instant.ofEpochMilli(1_700_000_000_000L))
    }

    @Test
    fun `rows finds the array wherever the venue wrapped it`() {
        val bare = node("""{ "data": [ { "a": 1 }, { "a": 2 } ] }""")
        val nested = node("""{ "data": { "list": [ { "a": 3 } ] } }""")
        val paths = listOf("data", "data.list", "data.positionList")
        assertThat(bare.rows(paths)).hasSize(2)
        assertThat(nested.rows(paths)).hasSize(1)
        assertThat(nested.rows(paths).single().int("a")).isEqualTo(3)
    }

    @Test
    fun `rows is empty when nothing matches, which pagination reads as the end of the data`() {
        assertThat(node("""{ "data": { "other": [] } }""").rows("data", "data.list")).isEmpty()
        assertThat(node("""{ "data": null }""").rows("data")).isEmpty()
        assertThat(node("{}").rows("data")).isEmpty()
    }
}
