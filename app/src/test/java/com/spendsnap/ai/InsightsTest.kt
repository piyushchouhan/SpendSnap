package com.spendsnap.ai

import com.spendsnap.domain.PaymentApp
import com.spendsnap.domain.ReviewIssue
import com.spendsnap.domain.VerificationStatus
import com.spendsnap.tx
import java.time.LocalDateTime
import java.time.ZoneId
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

class CategoryRulesTest {
    @Test fun `common Indian payees`() {
        assertEquals(SpendCategory.FOOD, CategoryRules.match("Swiggy"))
        assertEquals(SpendCategory.GROCERIES, CategoryRules.match("Lakshmi General Stores"))
        assertEquals(SpendCategory.TRANSPORT, CategoryRules.match("Petrol"))
        assertEquals(SpendCategory.TRANSPORT, CategoryRules.match("Shree Samarth petroleum"))
        assertEquals(SpendCategory.RENT, CategoryRules.match("October rent"))
        assertNull(CategoryRules.match("Ravi Kumar"))
    }

    @Test fun `word boundaries avoid false hits`() {
        assertNull(CategoryRules.match("Steam"))
        assertNull(CategoryRules.match("Parent"))
    }
}

class QueryEngineTest {
    private val zone = ZoneId.of("Asia/Kolkata")
    private val now = LocalDateTime.of(2026, 10, 21, 12, 0).atZone(zone).toInstant()
    private fun at(d: Int) = LocalDateTime.of(2026, 10, d, 10, 0).atZone(zone).toInstant().toEpochMilli()
    private val list = listOf(
        tx(1, 34900, at(21), description = "Swiggy"),
        tx(2, 24000, at(20), description = "Blue Tokai Coffee", app = PaymentApp.PHONEPE),
        tx(3, 8000, at(19), description = "Auto fare"),
        tx(4, 99900, at(2), description = "Ravi Kumar", category = "Rent"),
        tx(5, 50000, at(21), description = "Zomato", status = VerificationStatus.PENDING, issues = setOf(ReviewIssue.STATUS_UNVERIFIED)),
    )

    @Test fun `hinglish question parses locally`() {
        val q = QueryEngine.parseLocally("is mahine khane pe kitna kharch hua?")
        assertEquals(SpendQuery(QueryKind.TOTAL, Period.THIS_MONTH, setOf(SpendCategory.FOOD)), q)
    }

    @Test fun `category total counts only verified payments`() {
        val a = QueryEngine.answer(SpendQuery(QueryKind.TOTAL, Period.THIS_MONTH, setOf(SpendCategory.FOOD)), list, now, zone)
        assertEquals("₹589", a.figure) // Swiggy + Blue Tokai; the pending Zomato is not counted
        assertEquals(listOf(1L, 2L), a.rows.map { it.id })
    }

    @Test fun `largest uses the user's own category`() {
        val a = QueryEngine.answer(SpendQuery(QueryKind.LARGEST, Period.THIS_MONTH), list, now, zone)
        assertEquals("₹999", a.figure)
        assertTrue(a.text.contains("Ravi Kumar"))
    }

    @Test fun `compare apps`() {
        val a = QueryEngine.answer(SpendQuery(QueryKind.COMPARE_APPS, Period.THIS_WEEK), list, now, zone)
        assertEquals("₹429 vs ₹240", a.figure)
    }

    @Test fun `gemini json maps to a query and drops unknown values`() {
        val q = QueryEngine.fromJson(JSONObject("""{"kind":"TOTAL","period":"THIS_WEEK","categories":["FOOD","MADE_UP"],"apps":["PHONEPE"],"keyword":null}"""))
        assertEquals(SpendQuery(QueryKind.TOTAL, Period.THIS_WEEK, setOf(SpendCategory.FOOD), setOf(PaymentApp.PHONEPE)), q)
    }

    @Test fun `unsupported questions get a helpful reply`() {
        val a = QueryEngine.answer(QueryEngine.parseLocally("What's the weather?"), list, now, zone)
        assertEquals("", a.figure)
    }
}

class InsightsTest {
    private val zone = ZoneId.of("Asia/Kolkata")
    private val now = LocalDateTime.of(2026, 10, 21, 12, 0).atZone(zone).toInstant()
    private fun at(d: Int) = LocalDateTime.of(2026, 10, d, 10, 0).atZone(zone).toInstant().toEpochMilli()

    @Test fun `forecast counts rent once`() {
        val list = listOf(tx(1, 2_000_000, at(1), description = "Rent"), tx(2, 210_000, at(10), description = "Groceries"))
        val f = Insights.forecast(list, now, zone)
        // Rent 20,000 + 2,100 spread over 21 days → 3,100 for 31 days.
        assertEquals(2_000_000L + 310_000L, f.projectedPaise)
        assertEquals(31, f.daysInMonth)
    }

    @Test fun `summary guard rejects invented amounts`() {
        val week = Insights.week(listOf(tx(1, 34900, at(21), description = "Swiggy")), now, zone)
        val facts = Insights.summaryFacts(week)
        assertTrue(Insights.usesOnlyFacts("You spent ₹349 this week, mostly Swiggy.", facts))
        assertFalse(Insights.usesOnlyFacts("You spent ₹350 this week.", facts))
    }
}

class GeminiResponseTest {
    @Test fun `extracts text from candidates`() {
        val body = """{"candidates":[{"content":{"parts":[{"text":"{\"kind\":"},{"text":"\"TOTAL\"}"}]},"finishReason":"STOP"}]}"""
        assertEquals("""{"kind":"TOTAL"}""", GeminiClient.extractText(body))
    }

    @Test fun `blocked prompt is an error, not an empty answer`() {
        try {
            GeminiClient.extractText("""{"promptFeedback":{"blockReason":"SAFETY"}}""")
            fail()
        } catch (e: AiException) {
            assertTrue(e.message!!.contains("SAFETY"))
        }
    }
}
