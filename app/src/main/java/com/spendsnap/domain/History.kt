package com.spendsnap.domain

import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.format.DateTimeFormatter

enum class HistorySort(val label: String, val short: String) {
    NEWEST("Newest first", "Newest"),
    OLDEST("Oldest first", "Oldest"),
    HIGHEST("Amount: high to low", "Highest"),
    LOWEST("Amount: low to high", "Lowest"),
}

data class HistoryFilter(
    val query: String = "",
    val googlePay: Boolean = false,
    val phonePe: Boolean = false,
    val verified: Boolean = false,
    val unverified: Boolean = false,
    val sort: HistorySort = HistorySort.NEWEST,
) {
    val isFiltering: Boolean get() = query.isNotBlank() || googlePay || phonePe || verified || unverified
}

/** [totalPaise] counts only entries that count toward totals, same as the dashboard. */
data class HistoryGroup(val label: String, val totalPaise: Long, val items: List<Transaction>)

object History {
    private val SHORT_DAY = DateTimeFormatter.ofPattern("EEE, d MMM")

    fun dayLabel(millis: Long, today: LocalDate, zone: ZoneId): String {
        val date = Instant.ofEpochMilli(millis).atZone(zone).toLocalDate()
        return when (date) {
            today -> "Today"
            today.minusDays(1) -> "Yesterday"
            else -> SHORT_DAY.format(date)
        }
    }

    fun filter(all: List<Transaction>, f: HistoryFilter): List<Transaction> {
        val q = f.query.trim().lowercase()
        val digits = q.replace(Regex("""[₹,\s]"""), "")
        val list = all.filter { t ->
            val textHit = q.isEmpty() || t.description.orEmpty().lowercase().contains(q) ||
                (digits.isNotEmpty() && digits[0].isDigit() && t.amountPaise?.let(Inr::toEditable)?.contains(digits) == true)
            val appHit = !(f.googlePay || f.phonePe) ||
                (f.googlePay && t.paymentApp == PaymentApp.GOOGLE_PAY) || (f.phonePe && t.paymentApp == PaymentApp.PHONEPE)
            val statusHit = !(f.verified || f.unverified) || (f.verified && t.isVerified) || (f.unverified && t.isUnverified)
            textHit && appHit && statusHit
        }
        return when (f.sort) {
            HistorySort.NEWEST -> list.sortedByDescending { it.timestamp }
            HistorySort.OLDEST -> list.sortedBy { it.timestamp }
            HistorySort.HIGHEST -> list.sortedByDescending { it.amountPaise ?: -1 }
            HistorySort.LOWEST -> list.sortedBy { it.amountPaise ?: Long.MAX_VALUE }
        }
    }

    /** Date sorts group by day; amount sorts are one flat group. */
    fun group(list: List<Transaction>, sort: HistorySort, today: LocalDate, zone: ZoneId): List<HistoryGroup> {
        if (list.isEmpty()) return emptyList()
        val groups = when (sort) {
            HistorySort.NEWEST, HistorySort.OLDEST ->
                list.groupBy { dayLabel(it.timestamp, today, zone) }.map { (label, items) -> label to items }
            HistorySort.HIGHEST -> listOf("Highest amount first" to list)
            HistorySort.LOWEST -> listOf("Lowest amount first" to list)
        }
        return groups.map { (label, items) -> HistoryGroup(label, Totals.sum(items, Long.MIN_VALUE, Long.MAX_VALUE).paise, items) }
    }
}
