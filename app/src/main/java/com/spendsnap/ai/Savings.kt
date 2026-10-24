package com.spendsnap.ai

import com.spendsnap.domain.Inr
import com.spendsnap.domain.Transaction
import com.spendsnap.domain.countsTowardTotals
import java.time.Instant
import java.time.ZoneId
import org.json.JSONArray
import org.json.JSONObject

enum class SavingKind { SUBSCRIPTION, FOOD_DELIVERY, FREQUENT_SMALL, LATE_NIGHT, CATEGORY_UP }

/**
 * One place money could be saved. [monthlyPaise] is what it costs now (per month);
 * [savingPaise] is the estimate if the stated change is made. Both are computed on the phone.
 */
data class SavingOpportunity(
    val id: String,
    val kind: SavingKind,
    val title: String,
    val detail: String,
    val change: String,
    val monthlyPaise: Long,
    val savingPaise: Long,
    val evidence: List<Transaction>,
)

data class SavingsReport(
    val opportunities: List<SavingOpportunity>,
    val totalSavingPaise: Long,
    val daysTracked: Int,
) {
    /** Below this, patterns are mostly noise; the user is asked to keep tracking. */
    val enoughData: Boolean get() = daysTracked >= MIN_DAYS

    companion object {
        const val MIN_DAYS = 14
    }
}

/** Finds unnecessary spending from saved payments. Deterministic; the AI only rewords the result. */
object SavingsAnalyzer {
    private const val DAY = 86_400_000L
    private val FOOD_DELIVERY = Regex("""swiggy(?! ?instamart)|zomato|eatsure|eatclub|faasos|box8|domino|pizza hut|kfc|mcdonald|burger king""", RegexOption.IGNORE_CASE)
    private val DISCRETIONARY = setOf(SpendCategory.FOOD, SpendCategory.ENTERTAINMENT, SpendCategory.SHOPPING)
    private val ESSENTIAL = setOf(SpendCategory.RENT, SpendCategory.BILLS, SpendCategory.HEALTH, SpendCategory.GROCERIES)
    private val FUEL = Regex("""petrol|diesel|\bfuel\b|\bcng\b""", RegexOption.IGNORE_CASE)

    fun analyze(all: List<Transaction>, now: Instant, zone: ZoneId): SavingsReport {
        val nowMs = now.toEpochMilli()
        val counted = all.filter { it.countsTowardTotals && it.timestamp <= nowMs }
        // Rent, bills, medicine, groceries and fuel are needs, not leaks: never suggest cutting them.
        val optional = counted.filter { !essential(it) }
        val daysTracked = counted.minOfOrNull { it.timestamp }?.let { ((nowMs - it) / DAY).toInt() } ?: 0
        val last30 = optional.filter { it.timestamp > nowMs - 30 * DAY }
        val last90 = optional.filter { it.timestamp > nowMs - 90 * DAY }
        // Each payment backs at most one finding, so the total never counts it twice.
        val claimed = mutableSetOf<Long>()
        val found = mutableListOf<SavingOpportunity>()

        // 1. Subscriptions: same payee, near-identical amount, about a month apart.
        last90.groupBy { key(it) }.forEach { (_, list) ->
            val sorted = list.sortedBy { it.timestamp }
            if (sorted.size < 2) return@forEach
            val median = sorted.map { it.amountPaise!! }.sorted()[sorted.size / 2]
            val sameAmount = sorted.all { kotlin.math.abs(it.amountPaise!! - median) * 20 <= median }
            val monthly = sorted.zipWithNext().all { (a, b) -> (b.timestamp - a.timestamp) / DAY in 25..35 }
            if (sameAmount && monthly) {
                found += SavingOpportunity(
                    "sub-${key(sorted.last())}", SavingKind.SUBSCRIPTION,
                    "Subscription: ${name(sorted.last())}",
                    "${Inr.format(median)} about every month (${sorted.size} times in 90 days).",
                    "Cancel it if you don’t use it every week",
                    median, median, sorted.takeLast(3).reversed(),
                )
                claimed += sorted.map { it.id }
            }
        }

        // 2. Food delivery.
        val delivery = last30.filter { it.id !in claimed && FOOD_DELIVERY.containsMatchIn(it.description.orEmpty()) }
        if (delivery.size >= 3) {
            val total = sum(delivery)
            found += SavingOpportunity(
                "delivery", SavingKind.FOOD_DELIVERY,
                "Food delivery",
                "${delivery.size} orders in 30 days, ${Inr.format(total)} in all (avg ${Inr.format(total / delivery.size)}).",
                "Order half as often and cook or carry food the rest",
                total, total / 2, delivery.sortedByDescending { it.amountPaise }.take(3),
            )
            claimed += delivery.map { it.id }
        }

        // 3. Small payments that add up.
        last30.filter { it.id !in claimed }.groupBy { key(it) }.forEach { (k, list) ->
            val total = sum(list)
            if (list.size >= 4 && total / list.size <= 300_00) {
                found += SavingOpportunity(
                    "small-$k", SavingKind.FREQUENT_SMALL,
                    "Small but frequent: ${name(list.first())}",
                    "${list.size} payments in 30 days, avg ${Inr.format(total / list.size)}, ${Inr.format(total)} in all.",
                    "Skip every other one",
                    total, total / 2, list.sortedByDescending { it.timestamp }.take(3),
                )
                claimed += list.map { it.id }
            }
        }

        // 4. Late-night spending (11 PM to 4 AM).
        val night = last30.filter { it.id !in claimed && Instant.ofEpochMilli(it.timestamp).atZone(zone).hour.let { h -> h >= 23 || h < 4 } }
        if (night.size >= 3) {
            val total = sum(night)
            found += SavingOpportunity(
                "night", SavingKind.LATE_NIGHT,
                "Late-night spending",
                "${night.size} payments between 11 PM and 4 AM in 30 days, ${Inr.format(total)} in all.",
                "Wait until morning before late-night purchases",
                total, total / 2, night.sortedByDescending { it.amountPaise }.take(3),
            )
            claimed += night.map { it.id }
        }

        // 5. Discretionary categories running above last month.
        val today = now.atZone(zone).toLocalDate()
        val monthStart = today.withDayOfMonth(1).atStartOfDay(zone).toInstant().toEpochMilli()
        val prevStart = today.withDayOfMonth(1).minusMonths(1).atStartOfDay(zone).toInstant().toEpochMilli()
        val thisMonth = counted.filter { it.timestamp >= monthStart && it.id !in claimed }
        val prevMonth = counted.filter { it.timestamp in prevStart until monthStart }
        if (prevMonth.isNotEmpty() && today.dayOfMonth >= 7) {
            for (cat in DISCRETIONARY) {
                val current = thisMonth.filter { category(it) == cat }
                val before = sum(prevMonth.filter { category(it) == cat })
                val pace = sum(current) * today.lengthOfMonth() / today.dayOfMonth
                if (before > 0 && pace * 4 > before * 5 && pace - before >= 500_00) {
                    found += SavingOpportunity(
                        "up-${cat.name}", SavingKind.CATEGORY_UP,
                        "${cat.label} is up this month",
                        "On pace for ${Inr.format(pace)} vs ${Inr.format(before)} last month.",
                        "Get back to last month’s level",
                        pace, pace - before, current.sortedByDescending { it.amountPaise }.take(3),
                    )
                }
            }
        }

        val ranked = found.sortedByDescending { it.savingPaise }
        return SavingsReport(ranked, ranked.sumOf { it.savingPaise }, daysTracked)
    }

    /** The only data the AI gets for the savings plan: these findings, amounts pre-formatted. */
    fun facts(report: SavingsReport): JSONObject = JSONObject()
        .put("possibleMonthlySaving", Inr.format(report.totalSavingPaise))
        .put(
            "findings",
            JSONArray().apply {
                report.opportunities.take(5).forEach { o ->
                    put(
                        JSONObject().put("finding", o.title).put("detail", o.detail).put("suggestedChange", o.change)
                            .put("costsPerMonth", Inr.format(o.monthlyPaise)).put("couldSavePerMonth", Inr.format(o.savingPaise)),
                    )
                }
            },
        )

    /** Every ₹ figure the plan may mention: exactly the strings we supplied. */
    fun allowedAmounts(facts: JSONObject): Set<String> {
        val rupee = Regex("""₹[\d,]+(?:\.\d{1,2})?""")
        val findings = facts.optJSONArray("findings")
        return buildSet {
            add(facts.optString("possibleMonthlySaving"))
            for (i in 0 until (findings?.length() ?: 0)) {
                val o = findings!!.optJSONObject(i)
                listOf("detail", "costsPerMonth", "couldSavePerMonth").forEach { k -> rupee.findAll(o.optString(k)).forEach { add(it.value) } }
            }
        }
    }

    /** On-phone plan, used when AI is off or its wording fails the amount check. */
    fun localPlan(report: SavingsReport): List<String> =
        report.opportunities.take(3).map { "${it.change}: about ${Inr.format(it.savingPaise)} a month (${it.title.substringBefore(":").lowercase()})." }

    private fun key(t: Transaction) = t.description.orEmpty().trim().lowercase()
    private fun name(t: Transaction) = t.description?.trim().orEmpty().ifEmpty { "Unnamed payee" }
    private fun sum(list: List<Transaction>) = list.fold(0L) { acc, t -> Math.addExact(acc, t.amountPaise!!) }
    private fun category(t: Transaction) = SpendCategory.fromLabel(Insights.categoryLabel(t))
    private fun essential(t: Transaction) = category(t) in ESSENTIAL || FUEL.containsMatchIn(t.description.orEmpty())
}
