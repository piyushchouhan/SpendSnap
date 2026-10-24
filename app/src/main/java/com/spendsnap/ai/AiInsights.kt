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

/**
 * The three things Gemini is used for. Each sends the minimum needed:
 * - summary: pre-formatted weekly totals per category (no individual payments)
 * - ask: only the question text
 * - categorize: only payee descriptions the on-phone rules don't recognise (no amounts)
 * Every amount shown is computed on the phone by [Insights] / [QueryEngine].
 */
class AiInsights(
    private val repository: TransactionRepository,
    private val settings: AppSettings,
    private val gemini: GeminiClient,
) {
    val isConfigured: Boolean get() = gemini.isConfigured

    suspend fun summary(all: List<Transaction>, force: Boolean): Summary {
        val week = Insights.week(all, Instant.now(), ZoneId.systemDefault())
        val local = Insights.localSummary(week)
        if (week.count == 0) return Summary(local, fromAi = false)
        val facts = Insights.summaryFacts(week)
        val key = facts.toString()
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

    suspend fun ask(question: String, all: List<Transaction>): AskResult {
        val now = Instant.now()
        val zone = ZoneId.systemDefault()
        return try {
            val json = gemini.generate(
                system = "Turn the user's question about their own UPI spending (English, Hindi or Hinglish) into a query. " +
                    "Today is ${now.atZone(zone).toLocalDate()}. Categories: " +
                    SpendCategory.entries.joinToString { "${it.name} (${it.label})" } + ". " +
                    "Use keyword only for a specific payee or note named in the question. " +
                    "If the question is not about the user's spending, use kind UNSUPPORTED.",
                prompt = question,
                schema = QueryEngine.schema,
            )
            AskResult(QueryEngine.answer(QueryEngine.fromJson(JSONObject(json)), all, now, zone))
        } catch (e: Exception) {
            val note = (e as? AiException)?.message ?: "Gemini’s reply couldn’t be read"
            AskResult(QueryEngine.answer(QueryEngine.parseLocally(question), all, now, zone), note = "$note. Answered with on-phone rules.")
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
            gemini.generate(
                system = "Assign each Indian payment description (a shop, person or note) to one spending category. " +
                    "Categories: " + SpendCategory.entries.joinToString { "${it.name} (${it.label})" } +
                    ". A person's name with no other hint is OTHER. Return one item per description, copied exactly.",
                prompt = JSONArray(descriptions).toString(),
                schema = schema,
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
