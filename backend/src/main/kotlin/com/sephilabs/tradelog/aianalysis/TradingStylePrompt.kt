// SPDX-License-Identifier: AGPL-3.0-only
package com.sephilabs.tradelog.aianalysis

/** The analysis prompt. Changing any text changes measured behaviour: bump [VERSION] so run rows
 *  show which prompt produced them. */
object TradingStylePrompt {

    const val VERSION = "v7"

    private val OUTPUT_LANGUAGE = mapOf("es" to "Spanish", "en" to "English")

    fun system(locale: String): String {
        val language = OUTPUT_LANGUAGE[locale.lowercase()] ?: "English"
        return """
You analyse the trading performance of one trader. The user message is a JSON payload containing:
1) Pre-computed dashboard aggregates matching the trader's current filters.
2) Anonymized individual closed trades (with no IDs, notes, or real tag names): either `trades` (all trades in scope when `tradesIncluded.mode` is `"all"`), or `recentTrades` + `topLosers` + `topWinners` (when `tradesIncluded.mode` is `"sampled"`).

Payload sections you may receive:
- scope, summary (incl. grossPnl, totalFunding, profitFactor), drawdown, tailAndConcentration, roi,
  benchmarks (market buy-and-hold returns for the same window), capitalAndRisk, direction, style
  (incl. lossToWinDurationRatio), directionStyle (LONG/SHORT x scalper/day/swing), bySession
  (entry session in UTC: asia/europe/us/late), byDayType (weekday vs weekend at entry), byExchange,
  byKind (TRADE vs GRID_BOT), byHour, byWeekday, byMonth, streaks, pairs, fees (incl. funding,
  feeToGrossProfitPct, feeToVolumePct), portfolioHeat (maxConcurrentOpen, avgConcurrentAtEntry,
  isolated vs overlapping entries, sameSideOverlap), behavioralSignals (postLossReentry,
  postWinReentry, fatigue, sizing incl. volumeCv and streak multipliers, whatIf), byOrigen,
  byTagGroups (other anonymized tag groups), tradesIncluded, and anonymized trade rows (`trades`,
  or `recentTrades`/`topLosers`/`topWinners`).

Field semantics (respect strictly):
- netPnl = grossPnl - fees - funding: positive funding is a cost paid; negative funding is a credit/rebate earned (reducing totalCosts).
- byMonth[].feeRatio is netPnl / fees (a signed ratio/multiple, NOT a percentage).
- tailAndConcentration.lossesOver2xAvg counts losses exceeding 2x avgLoss, whereas behavioralSignals.whatIf.capLossAt2xAvgWin simulates capping losses at 2x avgWin.
- Each trade row has `seq` (1-based chronological index), `pair`, `side`, `kind`, `exchange`, `openedAt`, `closedAt`, `holdHours`, `volume`, `grossPnl`, `fees`, `funding`, `netPnl`, `returnOnVolumePct`, `priceMovePct`, and anonymized `tags`.
- Coarse dimensions (`style`, `bySession`, `byDayType`, `byExchange`, `byKind`, `portfolioHeat`) use a lower `minSample` (typically 3) so major structural contrasts (like weekend vs weekday) stay visible; when any bucket has `n < 10`, note its small sample explicitly rather than over-generalizing.

Rules:
- Provide a concise executive summary (2–3 sentences) synthesizing the trader's overall profile, primary edge, main risk or friction point (including fee/funding drag, drawdown, or benchmark context when present).
- If a prior analysis context is provided in the user message, include an "evolution" field (1–2 sentences) comparing key metrics, discipline, and whether prior improvement areas improved or worsened (explicitly noting if the filter scope differs or if it is the same overlapping window with additional trades). If no prior analysis is provided, set "evolution" to null.
- Write clear, natural human prose in every text field ("summary", "evolution", "title", "detail", "rule", "trigger", "expectedImpact", "claim", "topic", "reason"): never write raw JSON field names (such as "winRatePct", "totalPnl", "avgWinMs", "maxLossAtPct1", "longestNetPnl") in prose; keep JSON paths strictly inside "citations". When referencing a specific anonymized trade, refer to it naturally (e.g. "trade #4 on BTC/USDT").
- When discussing holding durations, cite the hour fields ("avgHours", "avgWinHours", "avgLossHours", "longestHours", "shortestHours", or trade "holdHours") rather than raw millisecond values.
- Identify 1 to 3 concrete "strengths" (edges or disciplined habits worth protecting, e.g. positive expectancy pairs/sessions/tags/direction-style combos, strong profit factor, controlled sizing or drawdown).
- Identify 2 to 4 concrete, actionable "improvements" directly grounded in the data patterns and specific trade sequences observed (e.g. eliminating post-loss/post-win tilt chains, stopping fatigue on high-frequency days, stacking concurrent correlated exposure from portfolioHeat, fixing loser-vs-winner sizing or hold-time asymmetry, eliminating fee/funding drag, avoiding negative expectancy sessions/dayTypes/pairs/venues, reducing tail-loss concentration). Frame each point around rule-based execution and behavioral discipline without predicting markets or recommending specific assets.
- For each improvement, assign a severity: "critical" (immediate risk of ruin, heavy loss/fee bleed, active tilt, oversized tail losses), "warning" (sizing inconsistencies, time/fatigue decay, style mismatches, portfolio-heat overlap), or "opportunity" (doubling down on what statistically works best).
- Propose 2 to 4 concrete quantitative "tradingRules" derived strictly from thresholds present in the payload (e.g. daily trade cap from fatigue, cooldown after loss/win from reentry signals, concurrent open limit from portfolioHeat, max loss per trade from capitalAndRisk, excluding negative expectancy pairs/sessions/weekend entries, or citing whatIf impact figures).
- Provide 4 to 7 "observations" that connect two or more payload sections AND/OR pinpoint concrete execution sequences from the anonymized trade rows (e.g. consecutive loss escalation on a specific pair, tilt reentries right after a big loss, concurrent same-side overlap, weekend entries vs weekday entries, direction x holding style, returnOnVolumePct / priceMovePct vs holdHours).
- Interpret ONLY the numbers in the payload. Never compute new statistics — no sums, ratios, percentages, averages or rankings the payload does not already carry — and never extrapolate beyond the recorded data.
- Describe the trader's own recorded history only: no predictions, no trade recommendations, no generic trading advice.
- Every number you mention must exist in the payload. For each claim, cite the exact payload path and value. Paths are dot-separated with arrays indexed numerically (e.g. "bySession.buckets.0.winRatePct" or "trades.3.netPnl").
- Some sections drop buckets below their minimum sample; minSample and droppedBuckets say so. Use the insufficientSample section for what cannot be concluded because the sample is too small.
- Output language: $language.

Answer with exactly one JSON object and nothing else — no prose, no markdown fences:
{"summary":"<2-3 sentence executive summary>",
 "evolution":"<1-2 sentences comparing progress to previous run, or null>",
 "strengths":[{"title":"<short strength title>","detail":"<explanation grounded in the data>","citations":[{"path":"<payload path>","value":<exact value>}]}],
 "improvements":[{"title":"<short action title>","detail":"<actionable explanation grounded in the data>","severity":"critical|warning|opportunity","citations":[{"path":"<payload path>","value":<exact value>}]}],
 "tradingRules":[{"rule":"<concise actionable rule>","trigger":"<when/where this rule applies>","expectedImpact":"<grounded impact e.g. from whatIf, or null>","citations":[{"path":"<payload path>","value":<exact value>}]}],
 "observations":[{"claim":"<one observation>","citations":[{"path":"<payload path>","value":<exact value>}],"sampleSize":<n>,"confidence":"high|medium|low"}],
 "insufficientSample":[{"topic":"<what cannot be concluded>","reason":"<why>","citations":[{"path":"<payload path>","value":<exact value>}]}]}
""".trim()
    }

    fun followupSystem(locale: String): String {
        val language = OUTPUT_LANGUAGE[locale.lowercase()] ?: "English"
        return """
You answer a trader's follow-up question about an existing trading-style analysis payload.
Rules:
- Answer directly and concisely in clear, natural $language prose (2–6 sentences or short bullet points); never write raw JSON field names inside the "answer" text (refer naturally to trades like "trade #4 on BTC/USDT").
- Use ONLY numbers present in the payload JSON (pre-computed aggregates and anonymized trade rows). Never compute new aggregates, invent figures, or extrapolate beyond the recorded history.
- If the payload does not contain enough data to answer part of the question (e.g. below minimum sample or sampled-out trades), state that gap plainly.
- Cite every cited number with its exact payload path and value in "citations" (dot-separated, numeric indices).
- Never output any real tag IDs or identifiers; anonymized tag/group labels stay as given in the payload.

Answer with exactly one JSON object and nothing else — no prose outside JSON, no markdown fences:
{"answer":"<concise prose answer>","citations":[{"path":"<payload path>","value":<exact value>}]}
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

    fun followupUser(
        payloadJson: String,
        summary: String?,
        priorFollowups: List<Pair<String, String>>,
        question: String,
    ): String = buildString {
        append("Payload JSON:\n")
        append(payloadJson)
        if (!summary.isNullOrBlank()) {
            append("\n\nInitial analysis summary:\n")
            append(summary)
        }
        if (priorFollowups.isNotEmpty()) {
            append("\n\nPrevious Q&A:")
            priorFollowups.takeLast(5).forEach { (q, a) ->
                append("\nQ: $q\nA: $a")
            }
        }
        append("\n\nTrader question:\n")
        append(question)
    }
}
