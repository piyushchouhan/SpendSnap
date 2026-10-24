package com.spendsnap.ai

import com.spendsnap.domain.ReviewIssue
import com.spendsnap.domain.VerificationStatus
import com.spendsnap.tx
import java.time.LocalDateTime
import java.time.ZoneId
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SavingsAnalyzerTest {
    private val zone = ZoneId.of("Asia/Kolkata")
    private val now = LocalDateTime.of(2026, 10, 24, 12, 0).atZone(zone).toInstant()
    private fun at(month: Int, day: Int, hour: Int = 13) = LocalDateTime.of(2026, month, day, hour, 0).atZone(zone).toInstant().toEpochMilli()
    private var id = 0L
    private fun pay(paise: Long, desc: String, month: Int, day: Int, hour: Int = 13) = tx(++id, paise, at(month, day, hour), description = desc)

    @Test fun `finds subscription, delivery, small habits and late night without double counting`() {
        val list = listOf(
            pay(64900, "Netflix", 8, 10), pay(64900, "Netflix", 9, 10), pay(64900, "Netflix", 10, 10),
            pay(34900, "Swiggy", 10, 2), pay(41000, "Zomato", 10, 9), pay(28000, "Swiggy", 10, 15), pay(39000, "Swiggy", 10, 22, hour = 23),
            pay(2000, "Chai Point", 10, 3), pay(2000, "Chai Point", 10, 8), pay(2500, "Chai Point", 10, 12), pay(2000, "Chai Point", 10, 20),
            pay(15000, "Late snack shop", 10, 5, hour = 23), pay(22000, "Night snacks", 10, 11, hour = 1), pay(30000, "Cab home", 10, 18, hour = 2),
        )
        val report = SavingsAnalyzer.analyze(list, now, zone)
        val kinds = report.opportunities.associateBy { it.kind }
        assertEquals(64900L, kinds.getValue(SavingKind.SUBSCRIPTION).savingPaise)
        // The 11 PM Swiggy order counts once, under delivery, not again under late night.
        assertEquals((34900L + 41000 + 28000 + 39000) / 2, kinds.getValue(SavingKind.FOOD_DELIVERY).savingPaise)
        assertEquals(8500L / 2, kinds.getValue(SavingKind.FREQUENT_SMALL).savingPaise)
        assertEquals(3, kinds.getValue(SavingKind.LATE_NIGHT).evidence.size)
        assertEquals(report.opportunities.sumOf { it.savingPaise }, report.totalSavingPaise)
        assertTrue(report.enoughData)
    }

    @Test fun `unverified payments are not analysed`() {
        val pending = (1..5).map {
            tx(it.toLong(), 2000, at(10, it + 1), description = "Chai", status = VerificationStatus.PENDING, issues = setOf(ReviewIssue.STATUS_UNVERIFIED))
        }
        assertTrue(SavingsAnalyzer.analyze(pending, now, zone).opportunities.isEmpty())
    }

    @Test fun `too little history asks to keep tracking`() {
        assertFalse(SavingsAnalyzer.analyze(listOf(pay(10000, "Swiggy", 10, 20)), now, zone).enoughData)
    }

    @Test fun `category running above last month`() {
        val list = listOf(pay(100000, "Myntra", 9, 5), pay(200000, "Myntra", 10, 3), pay(50000, "Decathlon", 10, 10))
        val up = SavingsAnalyzer.analyze(list, now, zone).opportunities.single { it.kind == SavingKind.CATEGORY_UP }
        // ₹2,500 in 24 days → ₹3,229 pace for 31 days vs ₹1,000 last month.
        assertEquals(250000L * 31 / 24 - 100000, up.savingPaise)
    }

    @Test fun `allowed amounts for the AI plan come only from the findings`() {
        val report = SavingsAnalyzer.analyze(listOf(pay(64900, "Netflix", 9, 10), pay(64900, "Netflix", 10, 10)), now, zone)
        val allowed = SavingsAnalyzer.allowedAmounts(SavingsAnalyzer.facts(report))
        assertTrue("₹649" in allowed)
        assertFalse("₹650" in allowed)
    }

    @Test fun `needs are never flagged as leaks`() {
        val list = (1..5).map { pay(8000, "Petrol", 10, it * 3) } +
            listOf(pay(2_000_000, "Rent", 8, 1), pay(2_000_000, "Rent", 9, 1), pay(2_000_000, "Rent", 10, 1)) +
            (1..4).map { pay(5000, "Apollo Pharmacy", 10, it * 4, hour = 23) }
        assertTrue(SavingsAnalyzer.analyze(list, now, zone).opportunities.isEmpty())
    }
}
