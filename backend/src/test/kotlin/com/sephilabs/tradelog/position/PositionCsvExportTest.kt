// SPDX-License-Identifier: AGPL-3.0-only
package com.sephilabs.tradelog.position

import com.jayway.jsonpath.JsonPath
import com.sephilabs.tradelog.connector.journal.JournalCsvConnector
import com.sephilabs.tradelog.identity.AuthHttpTestBase
import jakarta.servlet.http.Cookie
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.http.HttpHeaders
import org.springframework.http.MediaType
import org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.status

class PositionCsvExportTest : AuthHttpTestBase() {

    @Autowired
    lateinit var journalCsv: JournalCsvConnector

    @Test
    fun `exports every matching position across pages in the requested order`() {
        val session = sessionOf(register(newEmail("csv-export")))
        val profileId = createProfile(session)
        addTrade(session, profileId, "BTC-USDT", "LONG", "2026-03-01T10:00:00Z", entry = "100", exit = "110")
        addTrade(session, profileId, "ETH-USDT", "SHORT", "2026-03-02T10:00:00Z", entry = "50", exit = "40", note = "scalp; \"fast\"")
        addTrade(session, profileId, "SOL-USDT", "SHORT", "2026-03-03T10:00:00Z", entry = "20", exit = "25")

        val response = mockMvc.perform(
            get("/api/profiles/$profileId/positions/export").cookie(session)
                .param("side", "SHORT").param("sort", "closed_asc"),
        ).andExpect(status().isOk).andReturn().response

        assertThat(response.contentType).startsWith("text/csv")
        assertThat(response.getHeader(HttpHeaders.CONTENT_DISPOSITION)).matches("attachment; filename=\"tradelog-positions-\\d{4}-\\d{2}-\\d{2}\\.csv\"")
        val lines = response.contentAsString.trimEnd().lines()
        assertThat(lines[0]).isEqualTo(
            "symbol;side;opened_at;closed_at;quantity;entry_price;exit_price;realized_pnl;fees;funding;exchange;note;" +
                "net_pnl;pnl_currency;volume;leverage;investment;kind;source;tags",
        )
        assertThat(lines).hasSize(3)
        assertThat(lines[1]).startsWith("ETH-USDT;SHORT;2026-03-01T09:00:00Z;2026-03-02T10:00:00Z;1;50;40;10;1;0;")
        assertThat(lines[1]).contains(";\"scalp; \"\"fast\"\"\";9;USDT;90;;;TRADE;JOURNAL_CSV;")
        assertThat(lines[2]).startsWith("SOL-USDT;SHORT;")
    }

    @Test
    fun `an exported file re-imports through the journal csv connector`() {
        val session = sessionOf(register(newEmail("csv-roundtrip")))
        val profileId = createProfile(session)
        addTrade(session, profileId, "ETH-USDT", "SHORT", "2026-03-02T10:00:00Z", entry = "50", exit = "40", note = "a;b")

        val csv = mockMvc.perform(get("/api/profiles/$profileId/positions/export").cookie(session))
            .andExpect(status().isOk).andReturn().response.contentAsByteArray

        val record = journalCsv.parse(csv.inputStream()).single()
        assertThat(record.symbol.base).isEqualTo("ETH")
        assertThat(record.side).isEqualTo(PositionSide.SHORT)
        assertThat(record.realizedPnl).isEqualByComparingTo("10")
        assertThat(record.fees).isEqualByComparingTo("1")
        assertThat(record.note).isEqualTo("a;b")
    }

    @Test
    fun `refuses to export another user's profile`() {
        val owner = sessionOf(register(newEmail("csv-owner")))
        val profileId = createProfile(owner)
        val stranger = sessionOf(register(newEmail("csv-stranger")))

        val status = mockMvc.perform(get("/api/profiles/$profileId/positions/export").cookie(stranger))
            .andReturn().response.status
        assertThat(status).isIn(403, 404)
    }

    private fun createProfile(session: Cookie): String {
        val result = mockMvc.perform(
            post("/api/profiles").cookie(session).with(csrf())
                .contentType(MediaType.APPLICATION_JSON)
                .content("""{"kind":"PERSONAL","name":"Main"}"""),
        ).andExpect(status().isCreated).andReturn()
        return JsonPath.read(result.response.contentAsString, "$.id")
    }

    private fun addTrade(
        session: Cookie,
        profileId: String,
        symbol: String,
        side: String,
        closedAt: String,
        entry: String,
        exit: String,
        note: String? = null,
    ) {
        val noteJson = note?.let { "\"" + it.replace("\"", "\\\"") + "\"" } ?: "null"
        mockMvc.perform(
            post("/api/profiles/$profileId/positions").cookie(session).with(csrf())
                .contentType(MediaType.APPLICATION_JSON)
                .content(
                    """{"symbol":"$symbol","side":"$side","openedAt":"2026-03-01T09:00:00Z","closedAt":"$closedAt",
                        "qty":"1","entryPrice":"$entry","exitPrice":"$exit","fees":"1","funding":"0","note":$noteJson}""",
                ),
        ).andExpect(status().isCreated)
    }
}
