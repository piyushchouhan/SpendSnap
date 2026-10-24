package com.spendsnap.ai

import com.spendsnap.domain.Inr
import com.spendsnap.domain.PaymentApp
import com.spendsnap.domain.Totals
import com.spendsnap.domain.Transaction
import com.spendsnap.domain.countsTowardTotals
import com.spendsnap.domain.isUnverified
import java.time.Instant
import java.time.ZoneId
import org.json.JSONArray
import org.json.JSONObject

/** Fixed category set (from the AI Insights design). Stored on a transaction as its [label]. */
enum class SpendCategory(val label: String) {
    GROCERIES("Groceries"),
    FOOD("Food & cafés"),
    TRANSPORT("Transport"),
    BILLS("Bills & recharges"),
    RENT("Rent"),
    ENTERTAINMENT("Entertainment"),
    HEALTH("Health & fitness"),
    SHOPPING("Shopping"),
    OTHER("Other");

    companion object {
        fun fromLabel(label: String?): SpendCategory? = entries.firstOrNull { it.label.equals(label?.trim(), ignoreCase = true) }
        fun fromKey(key: String?): SpendCategory? = entries.firstOrNull { it.name.equals(key?.trim(), ignoreCase = true) }
    }
}

/** On-phone keyword rules. Gemini is only asked about descriptions these don't recognise. */
object CategoryRules {
    private fun rx(p: String) = Regex(p, RegexOption.IGNORE_CASE)
    private val RULES = listOf(
        SpendCategory.RENT to rx("""\brent\b"""),
        SpendCategory.GROCERIES to rx("""bigbasket|dmart|instamart|blinkit|zepto|general stores?|grocer|kirana|super ?market|fruits?|vegetables?|\bmilk\b|dairy"""),
        SpendCategory.FOOD to rx("""swiggy|zomato|\bchai\b|\btea\b|coffee|tokai|caf[eé]|restaurant|snacks?|\bfood\b|lunch|dinner|breakfast|bakery|\bhotel\b"""),
        SpendCategory.TRANSPORT to rx("""\bauto\b|rapido|\buber\b|\bola\b|petrol|diesel|\bfuel\b|\bshell\b|metro|\bcab\b|parking|fastag|irctc|\btrain\b|\bbus\b"""),
        SpendCategory.BILLS to rx("""airtel|bescom|\bjio\b|vodafone|electric|recharge|broadband|wi-?fi|\bgas\b|\bbill\b"""),
        SpendCategory.ENTERTAINMENT to rx("""\bpvr\b|inox|netflix|movie|hotstar|prime video|spotify|bookmyshow"""),
        SpendCategory.HEALTH to rx("""apollo|pharma|medical|medicine|chemist|hospital|clinic|\bcult\b|\bgym\b|doctor"""),
        SpendCategory.SHOPPING to rx("""myntra|decathlon|lenskart|croma|amazon|flipkart|ajio|meesho|nykaa|shopping|clothes"""),
    )

    fun match(description: String?): SpendCategory? =
        description?.let { d -> RULES.firstOrNull { it.second.containsMatchIn(d) }?.first }
}

data class CategoryShare(val label: String, val category: SpendCategory?, val paise: Long, val count: Int, val top: Transaction)

data class WeekStats(val paise: Long, val count: Int, val categories: List<CategoryShare>, val unverified: Int)

data class Forecast(val spentPaise: Long, val projectedPaise: Long, val day: Int, val daysInMonth: Int, val monthEnd: java.time.LocalDate)

/** Everything numeric on the Insights tab is computed here, on the phone. */
object Insights {
    /** The user's own category wins; then the on-phone rules; otherwise Other. */
    fun categoryLabel(t: Transaction): String =
        t.category?.trim()?.takeIf { it.isNotEmpty() } ?: CategoryRules.match(t.description)?.label ?: SpendCategory.OTHER.label

    fun breakdown(list: List<Transaction>): List<CategoryShare> =
        list.filter { it.countsTowardTotals }
            .groupBy { categoryLabel(it) }
            .map { (label, items) ->
                CategoryShare(
                    label = label,
                    category = SpendCategory.fromLabel(label),
                    paise = items.fold(0L) { acc, t -> Math.addExact(acc, t.amountPaise!!) },
                    count = items.size,
                    top = items.maxBy { it.amountPaise!! },
                )
            }
            .sortedByDescending { it.paise }

    fun week(all: List<Transaction>, now: Instant, zone: ZoneId): WeekStats {
        val start = Totals.periodStarts(now, zone).week
        val inWeek = all.filter { it.timestamp >= start }
        val total = Totals.sum(inWeek, start, Long.MAX_VALUE)
        return WeekStats(total.paise, total.count, breakdown(inWeek), inWeek.count { it.isUnverified })
    }

    fun month(all: List<Transaction>, now: Instant, zone: ZoneId): List<Transaction> {
        val start = Totals.periodStarts(now, zone).month
        return all.filter { it.timestamp >= start }
    }

    /** Straight-line pace for the rest of the month; rent is counted once, not projected. */
    fun forecast(all: List<Transaction>, now: Instant, zone: ZoneId): Forecast {
        val today = now.atZone(zone).toLocalDate()
        val days = today.lengthOfMonth()
        val month = month(all, now, zone)
        val spent = Totals.sum(month, Long.MIN_VALUE, Long.MAX_VALUE).paise
        val rent = breakdown(month).firstOrNull { it.category == SpendCategory.RENT }?.paise ?: 0L
        val projected = rent + (spent - rent) * days / today.dayOfMonth
        return Forecast(spent, projected, today.dayOfMonth, days, today.withDayOfMonth(days))
    }

    /** Used when Gemini is off or fails, and as the facts Gemini is allowed to reword. */
    fun localSummary(w: WeekStats): String {
        if (w.count == 0) return "No payments counted this week yet. Once you add or detect a few, a summary appears here."
        val top = w.categories.first()
        val pending = when (w.unverified) {
            0 -> " Everything is verified."
            1 -> " 1 payment still needs review."
            else -> " ${w.unverified} payments still need review."
        }
        return "You’ve spent ${Inr.format(w.paise)} across ${w.count} payment${if (w.count == 1) "" else "s"} since Monday. " +
            "${top.label} is the biggest share at ${Inr.format(top.paise)}, mostly ${top.top.description ?: "one payment"}.$pending"
    }

    /** The only data sent to Gemini for the summary: pre-formatted totals, no individual payments. */
    fun summaryFacts(w: WeekStats): JSONObject = JSONObject()
        .put("total", Inr.format(w.paise))
        .put("payments", w.count)
        .put("needsReview", w.unverified)
        .put(
            "categories",
            JSONArray().apply {
                w.categories.take(4).forEach { c ->
                    put(JSONObject().put("name", c.label).put("amount", Inr.format(c.paise)).put("payments", c.count).put("largestPayee", c.top.description ?: ""))
                }
            },
        )

    /** Guard against invented numbers: every ₹ figure in the AI text must be one we supplied. */
    fun usesOnlyFacts(text: String, facts: JSONObject): Boolean {
        // Read values, not facts.toString(): some org.json versions escape ₹ as \u20b9.
        val categories = facts.optJSONArray("categories")
        val allowed = buildSet {
            add(facts.optString("total"))
            for (i in 0 until (categories?.length() ?: 0)) add(categories!!.optJSONObject(i).optString("amount"))
        }
        return Regex("""₹\s?[\d,]+(?:\.\d{1,2})?""").findAll(text).all { it.value.replace(" ", "") in allowed }
    }
}

enum class Period(val phrase: String) { TODAY("today"), THIS_WEEK("this week"), THIS_MONTH("this month"), LAST_MONTH("last month"), ALL("since you started") }

enum class QueryKind { TOTAL, LARGEST, COMPARE_APPS, COUNT, UNSUPPORTED }

/** What the user asked, in a form the phone can answer exactly. Gemini only fills this in. */
data class SpendQuery(
    val kind: QueryKind,
    val period: Period,
    val categories: Set<SpendCategory> = emptySet(),
    val apps: Set<PaymentApp> = emptySet(),
    val keyword: String? = null,
)

data class Answer(val figure: String, val text: String, val rows: List<Transaction>, val basis: String)

object QueryEngine {
    /** Keyword fallback (English and common Hinglish) when Gemini is off or unreachable. */
    fun parseLocally(question: String): SpendQuery {
        val q = question.lowercase()
        val period = when {
            Regex("""today|\baaj\b""").containsMatchIn(q) -> Period.TODAY
            Regex("""last month|pichhle mahine|pichle mahine""").containsMatchIn(q) -> Period.LAST_MONTH
            Regex("""week|hafte|hafta""").containsMatchIn(q) -> Period.THIS_WEEK
            Regex("""month|mahine|mahina""").containsMatchIn(q) -> Period.THIS_MONTH
            else -> Period.ALL
        }
        val categories = buildSet {
            if (Regex("""grocer|kirana|sabzi""").containsMatchIn(q)) add(SpendCategory.GROCERIES)
            if (Regex("""food|\beat|dining|restaurant|caf[eé]|coffee|swiggy|zomato|khana|khane""").containsMatchIn(q)) add(SpendCategory.FOOD)
            if (Regex("""transport|travel|commute|cab|auto|fuel|petrol|diesel""").containsMatchIn(q)) add(SpendCategory.TRANSPORT)
            if (Regex("""bill|recharge|utilit|electric""").containsMatchIn(q)) add(SpendCategory.BILLS)
            if (Regex("""shop|cloth""").containsMatchIn(q)) add(SpendCategory.SHOPPING)
            if (Regex("""health|fitness|gym|medic""").containsMatchIn(q)) add(SpendCategory.HEALTH)
            if (Regex("""entertain|movie|stream""").containsMatchIn(q)) add(SpendCategory.ENTERTAINMENT)
            if (Regex("""\brent\b|kiraya""").containsMatchIn(q)) add(SpendCategory.RENT)
        }
        val kind = when {
            Regex("""biggest|largest|most expensive|highest|top|sabse bada""").containsMatchIn(q) -> QueryKind.LARGEST
            Regex("""google|gpay|phonepe|compare|which app|\bvs\b""").containsMatchIn(q) -> QueryKind.COMPARE_APPS
            Regex("""how many|kitni baar|count""").containsMatchIn(q) -> QueryKind.COUNT
            categories.isNotEmpty() || period != Period.ALL || Regex("""spend|spent|total|how much|kitna|kharch""").containsMatchIn(q) -> QueryKind.TOTAL
            else -> QueryKind.UNSUPPORTED
        }
        return SpendQuery(kind, period, categories)
    }

    /** Gemini's structured reply → [SpendQuery]. Unknown values are dropped, never guessed. */
    fun fromJson(json: JSONObject): SpendQuery {
        fun strings(key: String) = json.optJSONArray(key)?.let { a -> (0 until a.length()).map { a.optString(it) } }.orEmpty()
        return SpendQuery(
            kind = QueryKind.entries.firstOrNull { it.name == json.optString("kind") } ?: QueryKind.UNSUPPORTED,
            period = Period.entries.firstOrNull { it.name == json.optString("period") } ?: Period.ALL,
            categories = strings("categories").mapNotNull(SpendCategory::fromKey).toSet(),
            apps = strings("apps").mapNotNull { a -> PaymentApp.entries.firstOrNull { it.name == a } }.toSet(),
            keyword = json.optString("keyword").trim().takeIf { it.isNotEmpty() && it != "null" },
        )
    }

    /** JSON schema Gemini must follow when turning a question into a [SpendQuery]. */
    val schema: JSONObject = JSONObject("""
        {"type":"OBJECT","properties":{
          "kind":{"type":"STRING","enum":${names(QueryKind.entries)}},
          "period":{"type":"STRING","enum":${names(Period.entries)}},
          "categories":{"type":"ARRAY","items":{"type":"STRING","enum":${names(SpendCategory.entries)}}},
          "apps":{"type":"ARRAY","items":{"type":"STRING","enum":["GOOGLE_PAY","PHONEPE","CASH"]}},
          "keyword":{"type":"STRING","nullable":true}
        },"required":["kind","period"]}
    """.trimIndent())

    private fun names(values: List<Enum<*>>) = values.joinToString(",", "[", "]") { "\"${it.name}\"" }

    fun answer(query: SpendQuery, all: List<Transaction>, now: Instant, zone: ZoneId): Answer {
        if (query.kind == QueryKind.UNSUPPORTED) {
            return Answer("", "I can answer questions about your saved payments, like totals by category, your biggest payment, or Google Pay vs PhonePe.", emptyList(), "")
        }
        val starts = Totals.periodStarts(now, zone)
        val (from, until) = when (query.period) {
            Period.TODAY -> starts.day to Long.MAX_VALUE
            Period.THIS_WEEK -> starts.week to Long.MAX_VALUE
            Period.THIS_MONTH -> starts.month to Long.MAX_VALUE
            Period.LAST_MONTH -> {
                val first = now.atZone(zone).toLocalDate().withDayOfMonth(1).minusMonths(1)
                first.atStartOfDay(zone).toInstant().toEpochMilli() to starts.month
            }
            Period.ALL -> Long.MIN_VALUE to Long.MAX_VALUE
        }
        val inPeriod = all.filter { it.countsTowardTotals && it.timestamp in from until until }
        val matches = inPeriod.filter { t ->
            (query.categories.isEmpty() || SpendCategory.fromLabel(Insights.categoryLabel(t)) in query.categories) &&
                (query.apps.isEmpty() || t.paymentApp in query.apps) &&
                (query.keyword == null || t.description.orEmpty().contains(query.keyword, ignoreCase = true))
        }.sortedByDescending { it.amountPaise }
        val total = matches.sumOf { it.amountPaise!! }
        val what = when {
            query.keyword != null -> "on “${query.keyword}”"
            query.categories.isNotEmpty() -> "on " + query.categories.joinToString(" and ") { it.label }
            else -> ""
        }
        val p = query.period.phrase
        val basis = "Based on ${inPeriod.size} saved payment${if (inPeriod.size == 1) "" else "s"} $p"
        if (matches.isEmpty() && query.kind != QueryKind.COMPARE_APPS) {
            return Answer(Inr.format(0), "No payments ${what.ifEmpty { "" }} $p.".replace("  ", " "), emptyList(), basis)
        }
        return when (query.kind) {
            QueryKind.LARGEST -> {
                val t = matches.first()
                val next = matches.drop(1).take(2).mapNotNull { it.description }
                Answer(
                    Inr.format(t.amountPaise!!),
                    "Your largest payment $what $p was to ${t.description ?: "an unnamed payee"}." .replace("  ", " ") +
                        if (next.isNotEmpty()) " Next: ${next.joinToString(" and ")}." else "",
                    matches.take(3),
                    basis,
                )
            }
            QueryKind.COMPARE_APPS -> {
                fun sumFor(app: PaymentApp) = inPeriod.filter { it.paymentApp == app }.let { l -> l.size to l.sumOf { it.amountPaise!! } }
                val (gn, gp) = sumFor(PaymentApp.GOOGLE_PAY)
                val (pn, pp) = sumFor(PaymentApp.PHONEPE)
                Answer(
                    "${Inr.format(gp)} vs ${Inr.format(pp)}",
                    "Google Pay: $gn payments. PhonePe: $pn payments $p. " +
                        if (gp == pp) "Both are equal." else "You spend more through ${if (gp > pp) "Google Pay" else "PhonePe"}.",
                    emptyList(),
                    basis,
                )
            }
            QueryKind.COUNT -> Answer(
                "${matches.size} payment${if (matches.size == 1) "" else "s"}",
                "Totalling ${Inr.format(total)} $what $p.".replace("  ", " "),
                matches.take(3),
                basis,
            )
            else -> {
                val share = inPeriod.sumOf { it.amountPaise!! }.takeIf { it > 0 }?.let { total * 100 / it }
                Answer(
                    Inr.format(total),
                    "${matches.size} payment${if (matches.size == 1) "" else "s"} $what $p".replace("  ", " ") +
                        (if (what.isNotEmpty() && share != null) ", $share% of your spending." else ".") +
                        " Largest: ${matches.first().description ?: "an unnamed payee"}.",
                    matches.take(3),
                    basis,
                )
            }
        }
    }
}
