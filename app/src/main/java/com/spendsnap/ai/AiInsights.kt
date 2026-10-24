package com.spendsnap.ai

import com.spendsnap.AppSettings
import com.spendsnap.data.TransactionRepository
import com.spendsnap.domain.Transaction
import java.time.Instant
import java.time.ZoneId
import org.json.JSONArray
import org.json.JSONObject

data class Summary(val text: String, val fromAi: Boolean, val note: String? = null)

data class AskResult(val answer: Answer, val note: String? = null)

data class SavingsPlan(val lines: List<String>, val fromAi: Boolean, val note: String? = null)

/**
 * The three things the AI engine is used for. With the cloud engine each sends the minimum needed:
 * - summary: pre-formatted weekly totals per category (no individual payments)
 * - ask: only the question text
 * - categorize: only payee descriptions the on-phone rules don't recognise (no amounts)
 * Every amount shown is computed on the phone by [Insights] / [QueryEngine].
 */
class AiInsights(
    private val repository: TransactionRepository,
    private val settings: AppSettings,
    /** The engine chosen in Settings, read on every call so a switch applies immediately. */
    private val engine: () -> AiEngine,
) {
    val isConfigured: Boolean get() = engine().isConfigured

    suspend fun summary(all: List<Transaction>, force: Boolean): Summary {
        val week = Insights.week(all, Instant.now(), ZoneId.systemDefault())
        val local = Insights.localSummary(week)
        if (week.count == 0) return Summary(local, fromAi = false)
        val facts = Insights.summaryFacts(week)
        val gemini = engine()
        val key = "${gemini.onDevice}|$facts"
        if (!force && settings.summaryKey == key) settings.summaryText?.let { return Summary(it, fromAi = true) }
        return try {
            val text = gemini.generate(
                system = "You write a two-sentence, friendly summary of a person's spending this week for an Indian personal finance app. " +
                    "Use only the facts given. Copy rupee amounts exactly as written (Indian digit grouping). Never calculate new numbers. " +
                    "Mention the biggest category and, if needsReview > 0, that some payments need review. Plain text, no markdown.",
                prompt = facts.toString(),
            )
            if (!Insights.usesOnlyFacts(text, facts)) {
                Summary(local, fromAi = false, note = "Gemini’s wording had a figure that didn’t match your data, so the on-phone summary is shown.")
            } else {
                settings.summaryKey = key
                settings.summaryText = text
                Summary(text, fromAi = true)
            }
        } catch (e: AiException) {
            Summary(local, fromAi = false, note = e.message)
        }
    }

    /**
     * Turns the on-phone [SavingsReport] into three practical suggestions. The AI sees only the
     * findings (pre-formatted amounts) and may not introduce any other ₹ figure.
     */
    suspend fun savingsPlan(report: SavingsReport, force: Boolean): SavingsPlan {
        val local = SavingsAnalyzer.localPlan(report)
        if (report.opportunities.isEmpty()) return SavingsPlan(local, fromAi = false)
        val ai = engine()
        val facts = SavingsAnalyzer.facts(report)
        val key = "${ai.onDevice}|$facts"
        if (!force && settings.planKey == key) settings.planText?.let { return SavingsPlan(it.lines(), fromAi = true) }
        return try {
            val text = ai.generate(
                system = "You help an Indian user cut unnecessary spending. From the findings, write exactly 3 short, practical, " +
                    "specific suggestions, one per line, each starting with \"- \" and a verb. Mention the payee or habit by name. " +
                    "Copy rupee amounts exactly as given; never calculate or invent numbers. No other text.",
                prompt = facts.toString(),
            )
            val lines = text.lines().map { it.trim().removePrefix("-").removePrefix("*").replace(Regex("""^\d+[.)]\s*"""), "").trim() }
                .filter { it.isNotEmpty() }.take(3)
            val allowed = SavingsAnalyzer.allowedAmounts(facts)
            val invented = lines.any { line -> Regex("""₹\s?[\d,]+(?:\.\d{1,2})?""").findAll(line).any { it.value.replace(" ", "") !in allowed } }
            if (lines.isEmpty() || invented) {
                SavingsPlan(local, fromAi = false, note = "The AI’s wording had a figure that didn’t match your data, so the on-phone plan is shown.")
            } else {
                settings.planKey = key
                settings.planText = lines.joinToString("\n")
                SavingsPlan(lines, fromAi = true)
            }
        } catch (e: AiException) {
            SavingsPlan(local, fromAi = false, note = e.message)
        }
    }

    suspend fun ask(question: String, all: List<Transaction>): AskResult {
        val now = Instant.now()
        val zone = ZoneId.systemDefault()
        return try {
            val json = engine().generate(
                system = "Turn the user's question about their own UPI spending (English, Hindi or Hinglish) into a query. " +
                    "Today is ${now.atZone(zone).toLocalDate()}. Categories: " +
                    SpendCategory.entries.joinToString { "${it.name} (${it.label})" } + ". " +
                    "Use keyword only for a specific payee or note named in the question. " +
                    "If the question is not about the user's spending, use kind UNSUPPORTED.",
                prompt = question,
                schema = QueryEngine.schema,
            )
            AskResult(QueryEngine.answer(QueryEngine.fromJson(JSONObject(AiJson.extract(json))), all, now, zone))
        } catch (e: Exception) {
            val note = (e as? AiException)?.message ?: "Gemini’s reply couldn’t be read"
            AskResult(QueryEngine.answer(QueryEngine.parseLocally(question), all, now, zone), note = "${note.trimEnd('.')}. Answered with on-phone rules.")
        }
    }

    /** Fills in categories the rules can't, for entries the user hasn't categorised. Returns how many changed. */
    suspend fun categorizeMissing(all: List<Transaction>): Int {
        val unknown = all.filter { it.category.isNullOrBlank() && !it.description.isNullOrBlank() && CategoryRules.match(it.description) == null }
        val descriptions = unknown.mapNotNull { it.description?.trim() }.distinct().take(MAX_DESCRIPTIONS)
        if (descriptions.isEmpty()) return 0
        val schema = JSONObject(
            """{"type":"ARRAY","items":{"type":"OBJECT","properties":{
              "description":{"type":"STRING"},
              "category":{"type":"STRING","enum":${SpendCategory.entries.joinToString(",", "[", "]") { "\"${it.name}\"" }}}
            },"required":["description","category"]}}""",
        )
        val reply = JSONArray(
            AiJson.extract(
                engine().generate(
                system = "Assign each Indian payment description (a shop, person or note) to one spending category. " +
                    "Categories: " + SpendCategory.entries.joinToString { "${it.name} (${it.label})" } +
                    ". A person's name with no other hint is OTHER. Return one item per description, copied exactly.",
                    prompt = JSONArray(descriptions).toString(),
                    schema = schema,
                ),
            ),
        )
        val byDescription = (0 until reply.length()).mapNotNull { i ->
            val o = reply.optJSONObject(i) ?: return@mapNotNull null
            val cat = SpendCategory.fromKey(o.optString("category")) ?: return@mapNotNull null
            o.optString("description").trim() to cat
        }.toMap()
        var changed = 0
        for (t in unknown) {
            val cat = byDescription[t.description?.trim()] ?: continue
            repository.save(t.copy(category = cat.label))
            changed++
        }
        return changed
    }

    private companion object {
        const val MAX_DESCRIPTIONS = 40
    }
}
