package com.spendsnap.domain

import com.spendsnap.MINUTE
import com.spendsnap.T0
import com.spendsnap.tx
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDateTime
import java.time.ZoneId

class InrTest {
    @Test fun formatting() {
        assertEquals("₹0", Inr.format(0))
        assertEquals("₹1", Inr.format(100))
        assertEquals("₹999.05", Inr.format(99905))
        assertEquals("₹1,000", Inr.format(100000))
        assertEquals("₹1,00,000", Inr.format(1_00_000_00))
        assertEquals("₹12,34,56,789.50", Inr.format(12_34_56_789_50))
        assertEquals("-₹1,250.50", Inr.format(-125050))
    }

    @Test fun plain() {
        assertEquals("0.05", Inr.plain(5))
        assertEquals("1250.50", Inr.plain(125050))
    }

    @Test fun `user input`() {
        assertEquals(125050L, Inr.parseUserInput("1,250.5"))
        assertEquals(9900L, Inr.parseUserInput("₹ 99"))
        assertNull(Inr.parseUserInput("12.345"))
        assertNull(Inr.parseUserInput("abc"))
        assertNull(Inr.parseUserInput("0"))
    }

    @Test fun `no floating point drift`() {
        // 0.1 + 0.2 style sums must stay exact.
        val sum = Totals.sum(List(3) { tx(paise = 10, id = it + 1L) }, 0, Long.MAX_VALUE)
        assertEquals(30L, sum.paise)
    }
}

class TotalsTest {
    private val zone = ZoneId.of("Asia/Kolkata")
    private fun at(y: Int, m: Int, d: Int, h: Int = 12) = LocalDateTime.of(y, m, d, h, 0).atZone(zone).toInstant()

    @Test fun `day week month boundaries`() {
        val now = at(2026, 10, 21) // Wednesday
        val millis = { i: java.time.Instant -> i.toEpochMilli() }
        val list = listOf(
            tx(1, 10000, millis(at(2026, 10, 21, 9))), // today
            tx(2, 20000, millis(at(2026, 10, 20, 23))), // Tuesday, same week
            tx(3, 40000, millis(at(2026, 10, 18))), // Sunday, previous week, same month
            tx(4, 80000, millis(at(2026, 9, 30))), // previous month
        )
        val t = Totals.dashboard(list, now, zone)
        assertEquals(PeriodTotal(10000, 1), t.today)
        assertEquals(PeriodTotal(30000, 2), t.week)
        assertEquals(PeriodTotal(70000, 3), t.month)
        assertEquals(PeriodTotal(150000, 4), t.allTime)
        assertEquals(millis(at(2026, 9, 30)), t.sinceMillis)
    }

    @Test fun `only verified, known, non-duplicate amounts count`() {
        val list = listOf(
            tx(1, 100, status = VerificationStatus.SUCCESS_REPORTED),
            tx(2, 200, status = VerificationStatus.USER_CONFIRMED),
            tx(3, 400, status = VerificationStatus.FAILED),
            tx(4, 800, status = VerificationStatus.PENDING),
            tx(5, 1600, status = VerificationStatus.UNVERIFIED),
            tx(6, null, status = VerificationStatus.SUCCESS_REPORTED),
            tx(7, 3200, status = VerificationStatus.SUCCESS_REPORTED, duplicateOf = 1),
        )
        assertEquals(PeriodTotal(300, 2), Totals.sum(list, 0, Long.MAX_VALUE))
    }

    @Test fun `categories this month`() {
        val now = at(2026, 10, 21)
        val ms = now.toEpochMilli()
        val list = listOf(
            tx(1, 100, ms, category = "Food"),
            tx(2, 300, ms, category = "Travel"),
            tx(3, 150, ms, category = " Food "),
            tx(4, 999, ms),
        )
        assertEquals(listOf("Travel" to 300L, "Food" to 250L), Totals.dashboard(list, now, zone).monthByCategory)
    }
}

class DuplicateDetectorTest {
    private val decide = DuplicateDetector::decide

    @Test fun `same reference is the same payment`() {
        val existing = tx(1, ref = "629311845521")
        val d = decide(tx(ref = "629311845521", at = T0 + 60 * MINUTE), existing, emptyList())
        assertEquals(DuplicateDecision.SameTransaction(existing), d)
    }

    @Test fun `different references with equal amounts are distinct`() {
        val existing = tx(1, ref = "111111111111")
        assertEquals(DuplicateDecision.New, decide(tx(ref = "222222222222", at = T0 + MINUTE), null, listOf(existing)))
    }

    @Test fun `notification corroborates a single screen capture`() {
        val screen = tx(1)
        val notification = tx(method = ExtractionMethod.NOTIFICATION, at = T0 + MINUTE)
        assertEquals(DuplicateDecision.Corroborates(screen), decide(notification, null, listOf(screen)))
    }

    @Test fun `same amount again from the same source is a new payment`() {
        assertEquals(DuplicateDecision.New, decide(tx(at = T0 + MINUTE), null, listOf(tx(1))))
        val n = tx(1, method = ExtractionMethod.NOTIFICATION)
        assertEquals(DuplicateDecision.New, decide(tx(method = ExtractionMethod.NOTIFICATION, at = T0 + MINUTE), null, listOf(n)))
    }

    @Test fun `OCR retry is a possible duplicate, never auto-merged`() {
        val first = tx(1, method = ExtractionMethod.OCR, status = VerificationStatus.USER_CONFIRMED)
        val retry = tx(method = ExtractionMethod.OCR, status = VerificationStatus.USER_CONFIRMED, at = T0 + MINUTE)
        assertEquals(DuplicateDecision.PossibleDuplicate(first), decide(retry, null, listOf(first)))
    }

    @Test fun `two equal earlier entries make it ambiguous`() {
        val a = tx(1)
        val b = tx(2, at = T0 + MINUTE)
        val n = tx(method = ExtractionMethod.NOTIFICATION, at = T0 + 2 * MINUTE)
        assertEquals(DuplicateDecision.PossibleDuplicate(b), decide(n, null, listOf(a, b)))
    }

    @Test fun `outside the time window is new`() =
        assertEquals(DuplicateDecision.New, decide(tx(at = T0 + 6 * MINUTE), null, listOf(tx(1))))

    @Test fun `different amount or app is new`() {
        assertEquals(DuplicateDecision.New, decide(tx(paise = 200), null, listOf(tx(1))))
        assertEquals(DuplicateDecision.New, decide(tx(app = PaymentApp.PHONEPE), null, listOf(tx(1))))
    }

    @Test fun `failed then successful retry is a different payment`() {
        val failed = tx(1, status = VerificationStatus.FAILED)
        assertEquals(DuplicateDecision.New, decide(tx(at = T0 + MINUTE), null, listOf(failed)))
    }

    @Test fun `manual entries are never matched`() =
        assertEquals(DuplicateDecision.New, decide(tx(), null, listOf(tx(1, method = ExtractionMethod.MANUAL))))

    @Test fun `unknown amount cannot be matched`() =
        assertEquals(DuplicateDecision.New, decide(tx(paise = null), null, listOf(tx(1))))
}

class VerificationStateTest {
    private val combine = DuplicateDetector::combineStatus

    @Test fun `pending then success`() =
        assertEquals(VerificationStatus.SUCCESS_REPORTED, combine(VerificationStatus.PENDING, VerificationStatus.SUCCESS_REPORTED, false))

    @Test fun `independent success signals corroborate`() =
        assertEquals(VerificationStatus.SUCCESS_CORROBORATED, combine(VerificationStatus.SUCCESS_REPORTED, VerificationStatus.SUCCESS_REPORTED, true))

    @Test fun `same source repeated does not corroborate`() =
        assertEquals(VerificationStatus.SUCCESS_REPORTED, combine(VerificationStatus.SUCCESS_REPORTED, VerificationStatus.SUCCESS_REPORTED, false))

    @Test fun `success versus failure is unverified`() =
        assertEquals(VerificationStatus.UNVERIFIED, combine(VerificationStatus.SUCCESS_REPORTED, VerificationStatus.FAILED, true))

    @Test fun `pending then failed`() =
        assertEquals(VerificationStatus.FAILED, combine(VerificationStatus.PENDING, VerificationStatus.FAILED, true))

    @Test fun `user decision wins`() =
        assertEquals(VerificationStatus.USER_CONFIRMED, combine(VerificationStatus.USER_CONFIRMED, VerificationStatus.FAILED, true))

    @Test fun `merge fills gaps and clears resolved issues`() {
        val pending = tx(1, status = VerificationStatus.PENDING, description = null,
            issues = setOf(ReviewIssue.STATUS_UNVERIFIED, ReviewIssue.DESCRIPTION_MISSING))
        val success = tx(method = ExtractionMethod.NOTIFICATION, description = "Paid to Ravi", ref = "629311845521")
        val merged = DuplicateDetector.merge(pending, success, independentSource = true)
        assertEquals(VerificationStatus.SUCCESS_REPORTED, merged.verificationStatus)
        assertEquals("Paid to Ravi", merged.description)
        assertEquals("629311845521", merged.transactionReference)
        assertTrue(merged.reviewIssues.isEmpty())
        assertEquals(1L, merged.id)
    }

    @Test fun `conflicting merge is flagged`() {
        val merged = DuplicateDetector.merge(tx(1), tx(status = VerificationStatus.FAILED), independentSource = true)
        assertEquals(VerificationStatus.UNVERIFIED, merged.verificationStatus)
        assertTrue(ReviewIssue.STATUS_CONFLICT in merged.reviewIssues)
    }
}

class CsvExporterTest {
    @Test fun `escapes and neutralises formulas`() {
        assertEquals("plain", CsvExporter.escape("plain"))
        assertEquals("\"a,b\"", CsvExporter.escape("a,b"))
        assertEquals("\"say \"\"hi\"\"\"", CsvExporter.escape("say \"hi\""))
        assertEquals("'=SUM(A1)", CsvExporter.escape("=SUM(A1)"))
    }

    @Test fun `rows use exact amounts`() {
        val csv = CsvExporter.toCsv(listOf(tx(7, 125050, description = "Chai, snacks")), ZoneId.of("Asia/Kolkata"))
        val row = csv.lines()[1]
        assertTrue(row, row.startsWith("7,"))
        assertTrue(row, row.contains(",\"Chai, snacks\",1250.50,125050,INR,Google Pay,SUCCESS_REPORTED,ACCESSIBILITY,"))
    }
}

class LikelyDuplicateTest {
    private val swiggy = tx(1, 34900, description = "Swiggy", method = ExtractionMethod.ACCESSIBILITY)

    @Test fun `same amount and payee within a day`() =
        assertEquals(swiggy, DuplicateDetector.findLikelyDuplicate(34900, " swiggy ", null, T0 + 60 * MINUTE, listOf(swiggy)))

    @Test fun `same reference regardless of text`() {
        val withRef = swiggy.copy(transactionReference = "629311845521")
        assertEquals(withRef, DuplicateDetector.findLikelyDuplicate(100, "Other", "629311845521", T0, listOf(withRef)))
    }

    @Test fun `different payee, amount, old, or failed is not a duplicate`() {
        assertNull(DuplicateDetector.findLikelyDuplicate(34900, "Zomato", null, T0, listOf(swiggy)))
        assertNull(DuplicateDetector.findLikelyDuplicate(35000, "Swiggy", null, T0, listOf(swiggy)))
        assertNull(DuplicateDetector.findLikelyDuplicate(34900, "Swiggy", null, T0 + 25 * 60 * MINUTE, listOf(swiggy)))
        assertNull(DuplicateDetector.findLikelyDuplicate(34900, "Swiggy", null, T0, listOf(swiggy.copy(verificationStatus = VerificationStatus.FAILED))))
    }
}

class HistoryTest {
    private val zone = ZoneId.of("Asia/Kolkata")
    private val today = java.time.LocalDate.of(2026, 10, 20)
    private fun at(d: Int, h: Int) = LocalDateTime.of(2026, 10, d, h, 0).atZone(zone).toInstant().toEpochMilli()
    private val list = listOf(
        tx(1, 34900, at(20, 13), description = "Swiggy"),
        tx(2, 8000, at(20, 8), description = "Auto fare", app = PaymentApp.PHONEPE, status = VerificationStatus.PENDING,
            issues = setOf(ReviewIssue.STATUS_UNVERIFIED)),
        tx(3, 128650, at(19, 19), description = "BigBasket"),
        tx(4, 48600, at(18, 21), description = "Zomato", app = PaymentApp.PHONEPE),
    )

    @Test fun `groups by day with counted totals`() {
        val groups = History.group(History.filter(list, HistoryFilter()), HistorySort.NEWEST, today, zone)
        assertEquals(listOf("Today", "Yesterday", "Sun, 18 Oct"), groups.map { it.label })
        // The pending ₹80 is shown but not counted.
        assertEquals(listOf(34900L, 128650L, 48600L), groups.map { it.totalPaise })
    }

    @Test fun `search by payee or amount`() {
        assertEquals(listOf(3L), History.filter(list, HistoryFilter(query = "basket")).map { it.id })
        assertEquals(listOf(3L), History.filter(list, HistoryFilter(query = "₹1,286")).map { it.id })
    }

    @Test fun `app and status chips`() {
        assertEquals(listOf(2L, 4L), History.filter(list, HistoryFilter(phonePe = true)).map { it.id })
        assertEquals(listOf(2L), History.filter(list, HistoryFilter(unverified = true)).map { it.id })
        assertEquals(listOf(4L), History.filter(list, HistoryFilter(phonePe = true, verified = true)).map { it.id })
    }

    @Test fun `amount sorts are one flat group`() {
        val sorted = History.filter(list, HistoryFilter(sort = HistorySort.HIGHEST))
        assertEquals(listOf(3L, 4L, 1L, 2L), sorted.map { it.id })
        assertEquals(listOf("Highest amount first"), History.group(sorted, HistorySort.HIGHEST, today, zone).map { it.label })
    }
}

class ReviewBeforeSavingTest {
    @Test fun `awaiting review is not counted until checked`() {
        val waiting = tx(1, 500, issues = setOf(ReviewIssue.AWAITING_REVIEW))
        assertEquals(PeriodTotal(0, 0), Totals.sum(listOf(waiting), 0, Long.MAX_VALUE))
        assertTrue(waiting.isUnverified)
        // Merging a later success signal keeps it waiting for the user.
        val merged = DuplicateDetector.merge(waiting, tx(method = ExtractionMethod.NOTIFICATION), independentSource = true)
        assertTrue(ReviewIssue.AWAITING_REVIEW in merged.reviewIssues)
    }
}

class CashTest {
    @Test fun `cash is filterable and never detected`() {
        val list = listOf(tx(1, app = PaymentApp.CASH, description = "Chai"), tx(2, app = PaymentApp.GOOGLE_PAY))
        assertEquals(listOf(1L), History.filter(list, HistoryFilter(cash = true)).map { it.id })
        assertNull(PaymentApp.fromPackage(null))
        assertNull(PaymentApp.CASH.packageName)
    }
}

class IncomeTest {
    @Test fun `income never counts as spending and has its own total`() {
        val salary = tx(1, 5_000_000, description = "Salary").copy(direction = Direction.INCOME)
        val chai = tx(2, 2000, description = "Chai")
        assertEquals(PeriodTotal(2000, 1), Totals.sum(listOf(salary, chai), 0, Long.MAX_VALUE))
        assertEquals(PeriodTotal(5_000_000, 1), Totals.income(listOf(salary, chai), 0, Long.MAX_VALUE))
        assertTrue(salary.isVerified)
    }

    @Test fun `income and an equal payment are never duplicates`() {
        val paid = tx(1, 50000)
        val received = tx(paise = 50000, at = T0 + MINUTE).copy(direction = Direction.INCOME, extractionMethod = ExtractionMethod.NOTIFICATION)
        assertEquals(DuplicateDecision.New, DuplicateDetector.decide(received, null, listOf(paid)))
    }
}
