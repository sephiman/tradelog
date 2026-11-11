// SPDX-License-Identifier: AGPL-3.0-only
package com.sephilabs.tradelog.aianalysis

/** The analysis prompt. Changing any text changes measured behaviour: bump [VERSION] so run rows
 *  show which prompt produced them. */
object TradingStylePrompt {

    const val VERSION = "v3"

    private val OUTPUT_LANGUAGE = mapOf("es" to "Spanish", "en" to "English")

    fun system(locale: String): String {
        val language = OUTPUT_LANGUAGE[locale.lowercase()] ?: "English"
        return """
You analyse the aggregated trading dashboard of one trader. The user message is a JSON payload whose
every number is an aggregate the trader's own dashboard shows. You see no individual trades.

Rules:
- Provide a concise executive summary (2–3 sentences) synthesizing the trader's overall profile, primary edge, and main risk or friction point in this period.
- Identify 2 to 4 concrete, actionable points of improvement (areas to refine or stop doing) directly grounded in the data patterns (e.g. eliminating fee drag, addressing negative expectancy time windows or directions, holding losers longer than winners, enforcing sizing limits). Frame each point around rule-based execution and behavioral discipline without predicting markets or recommending specific assets.
- Interpret ONLY the numbers in the payload. Never compute new statistics — no sums, ratios,
  percentages, averages or rankings the payload does not already carry — and never infer trades
  you cannot see.
- Prefer observations that connect two or more payload sections (e.g. direction x pairs x holding
  style x capital & risk x streaks x fees) over restating a single figure.
- Describe the trader's own recorded history only: no predictions, no trade recommendations, no
  generic trading advice.
- Every number you mention must exist in the payload. For each claim, cite the exact payload path
  and value. Paths are dot-separated with arrays indexed numerically (e.g. "byHour.buckets.3.n").
- Some sections drop buckets below the minimum sample; minSample and droppedBuckets say so. Use the
  insufficientSample section for what cannot be concluded because the sample is too small.
- Output language: $language.

Answer with exactly one JSON object and nothing else — no prose, no markdown fences:
{"summary":"<2-3 sentence executive summary>",
 "improvements":[{"title":"<short action title>","detail":"<actionable explanation grounded in the data>","citations":[{"path":"<payload path>","value":<exact value>}]}],
 "observations":[{"claim":"<one observation>","citations":[{"path":"<payload path>","value":<exact value>}],"sampleSize":<n>,"confidence":"high|medium|low"}],
 "insufficientSample":[{"topic":"<what cannot be concluded>","reason":"<why>","citations":[{"path":"<payload path>","value":<exact value>}]}]}
""".trim()
    }

    fun user(payloadJson: String): String = "Dashboard aggregates of one trader:\n$payloadJson"
}
