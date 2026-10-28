package com.spendsnap.domain

import java.time.DayOfWeek
import java.time.Instant
import java.time.ZoneId
import java.time.temporal.TemporalAdjusters

data class PeriodTotal(val paise: Long, val count: Int)

data class DashboardTotals(
    val allTime: PeriodTotal,
    /** Earliest counted payment, for "recorded since …". Null when nothing counts yet. */
    val sinceMillis: Long?,
    val today: PeriodTotal,
    val week: PeriodTotal,
    val month: PeriodTotal,
    /** Category totals for the current month, largest first. Uncategorised spend is left out. */
    val monthByCategory: List<Pair<String, Long>>,
    /** Money received this month; kept apart from spending. */
    val monthIncome: PeriodTotal = PeriodTotal(0, 0),
)

data class PeriodStarts(val day: Long, val week: Long, val month: Long)

object Totals {
    /** Weeks start on Monday (ISO). */
    fun periodStarts(now: Instant, zone: ZoneId): PeriodStarts {
        val today = now.atZone(zone).toLocalDate()
        fun millis(date: java.time.LocalDate) = date.atStartOfDay(zone).toInstant().toEpochMilli()
        return PeriodStarts(
            day = millis(today),
            week = millis(today.with(TemporalAdjusters.previousOrSame(DayOfWeek.MONDAY))),
            month = millis(today.withDayOfMonth(1)),
        )
    }

    fun sum(transactions: List<Transaction>, fromMillis: Long, untilMillis: Long): PeriodTotal {
        var paise = 0L
        var count = 0
        for (t in transactions) {
            if (!t.countsTowardTotals || t.timestamp < fromMillis || t.timestamp >= untilMillis) continue
            paise = Math.addExact(paise, t.amountPaise!!)
            count++
        }
        return PeriodTotal(paise, count)
    }

    fun income(transactions: List<Transaction>, fromMillis: Long, untilMillis: Long): PeriodTotal {
        var paise = 0L
        var count = 0
        for (t in transactions) {
            if (!t.countsAsIncome || t.timestamp < fromMillis || t.timestamp >= untilMillis) continue
            paise = Math.addExact(paise, t.amountPaise!!)
            count++
        }
        return PeriodTotal(paise, count)
    }

    fun dashboard(transactions: List<Transaction>, now: Instant, zone: ZoneId): DashboardTotals {
        val starts = periodStarts(now, zone)
        val end = Long.MAX_VALUE
        val byCategory = transactions
            .filter { it.countsTowardTotals && it.timestamp >= starts.month && !it.category.isNullOrBlank() }
            .groupBy { it.category!!.trim() }
            .mapValues { (_, list) -> list.fold(0L) { acc, t -> Math.addExact(acc, t.amountPaise!!) } }
            .toList()
            .sortedByDescending { it.second }
        return DashboardTotals(
            allTime = sum(transactions, Long.MIN_VALUE, end),
            sinceMillis = transactions.filter { it.countsTowardTotals }.minOfOrNull { it.timestamp },
            today = sum(transactions, starts.day, end),
            week = sum(transactions, starts.week, end),
            month = sum(transactions, starts.month, end),
            monthByCategory = byCategory,
            monthIncome = income(transactions, starts.month, end),
        )
    }
}
