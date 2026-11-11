// SPDX-License-Identifier: AGPL-3.0-only
package com.sephilabs.tradelog.aianalysis

/** The analysis prompt. Changing any text changes measured behaviour: bump [VERSION] so run rows
 *  show which prompt produced them. */
object TradingStylePrompt {

    const val VERSION = "v4"

    private val OUTPUT_LANGUAGE = mapOf("es" to "Spanish", "en" to "English")

    fun system(locale: String): String {
        val language = OUTPUT_LANGUAGE[locale.lowercase()] ?: "English"
        return """
You analyse the aggregated trading dashboard of one trader. The user message is a JSON payload whose
every number is an aggregate the trader's own dashboard shows. You see no individual trades.

Rules:
- Provide a concise executive summary (2–3 sentences) synthesizing the trader's overall profile, primary edge, and main risk or friction point in this period.
- If a prior analysis summary is provided in the user message, include an "evolution" field (1–2 sentences) assessing whether key metrics, discipline, or leaks have improved or worsened since that prior run. If no prior analysis is provided, set "evolution" to null.
- Identify 2 to 4 concrete, actionable points of improvement directly grounded in the data patterns (e.g. eliminating revenge trading after losses, stopping fatigue on high-frequency days, fixing loser-vs-winner sizing asymmetry, eliminating fee drag, avoiding negative expectancy time windows). Frame each point around rule-based execution and behavioral discipline without predicting markets or recommending specific assets.
- For each improvement, assign a severity: "critical" (immediate risk of ruin, heavy loss/fee bleed, active tilt), "warning" (sizing inconsistencies, time/fatigue decay, style mismatches), or "opportunity" (doubling down on what statistically works best).
- Interpret ONLY the numbers in the payload. Never compute new statistics — no sums, ratios,
  percentages, averages or rankings the payload does not already carry — and never infer trades
  you cannot see.
- Prefer observations that connect two or more payload sections (e.g. direction x pairs x holding
  style x capital & risk x streaks x fees x behavioral signals) over restating a single figure.
- Describe the trader's own recorded history only: no predictions, no trade recommendations, no
  generic trading advice.
- Every number you mention must exist in the payload. For each claim, cite the exact payload path
  and value. Paths are dot-separated with arrays indexed numerically (e.g. "byHour.buckets.3.n").
- Some sections drop buckets below the minimum sample; minSample and droppedBuckets say so. Use the
  insufficientSample section for what cannot be concluded because the sample is too small.
- Output language: $language.

Answer with exactly one JSON object and nothing else — no prose, no markdown fences:
{"summary":"<2-3 sentence executive summary>",
 "evolution":"<1-2 sentences comparing progress to previous run, or null>",
 "improvements":[{"title":"<short action title>","detail":"<actionable explanation grounded in the data>","severity":"critical|warning|opportunity","citations":[{"path":"<payload path>","value":<exact value>}]}],
 "observations":[{"claim":"<one observation>","citations":[{"path":"<payload path>","value":<exact value>}],"sampleSize":<n>,"confidence":"high|medium|low"}],
 "insufficientSample":[{"topic":"<what cannot be concluded>","reason":"<why>","citations":[{"path":"<payload path>","value":<exact value>}]}]}
""".trim()
    }

    fun user(payloadJson: String, previousRunContext: String? = null): String = buildString {
        append("Dashboard aggregates of one trader:\n")
        append(payloadJson)
        if (!previousRunContext.isNullOrBlank()) {
            append("\n\nPrior analysis summary for context:\n")
            append(previousRunContext)
        }
    }
}
