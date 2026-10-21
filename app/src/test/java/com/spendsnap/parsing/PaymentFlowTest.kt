package com.spendsnap.parsing

import com.spendsnap.domain.ScreenStatus
import com.spendsnap.fixture
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class PaymentFormParserTest {
    // Mirrors the PhonePe amount-entry screen: old bubble above, typed amount and note in fields.
    private val form = listOf(
        ScreenNode("Contact Name"),
        ScreenNode("₹1"), ScreenNode("PAID"), ScreenNode("Testing"), ScreenNode("10:30 PM"),
        ScreenNode("Transfer Money to"),
        ScreenNode("Banking name: Ravi Kumar"),
        ScreenNode("Lunch", editable = true),
        ScreenNode("You are paying instantly with your fingerprint"),
        ScreenNode("₹"),
        ScreenNode("250", editable = true),
        ScreenNode("PAY", clickable = true),
    )

    @Test fun `typed fields are the truth, not bubbles`() =
        assertEquals(PaymentDraft(25000, "Lunch", "Ravi Kumar"), PaymentFormParser.parse(form))

    @Test fun `no typed amount means no draft`() =
        assertNull(PaymentFormParser.parse(form.filterNot { it.text == "250" }))

    @Test fun `empty note`() =
        assertNull(PaymentFormParser.parse(form.filterNot { it.text == "Lunch" })!!.note)

    @Test fun `pay buttons`() {
        assertTrue(PaymentFormParser.isPayButton("PAY"))
        assertTrue(PaymentFormParser.isPayButton(" Pay ₹1 "))
        assertFalse(PaymentFormParser.isPayButton("Pay anyone"))
        assertFalse(PaymentFormParser.isPayButton("Tap & Pay"))
    }
}

class ChatBubbleParserTest {
    private val bubbles = ChatBubbleParser.parse(fixture("phonepe_chat_real"))

    @Test fun `parses real PhonePe chat`() {
        assertEquals(
            listOf(
                ChatBubble(400000, ScreenStatus.SUCCESS, null, 8 * 60 + 5),
                ChatBubble(100, ScreenStatus.SUCCESS, "Food", 22 * 60 + 23),
                ChatBubble(100, ScreenStatus.SUCCESS, "bank", 22 * 60 + 25),
                ChatBubble(100, ScreenStatus.SUCCESS, "Testing", 22 * 60 + 30),
            ),
            bubbles,
        )
    }

    @Test fun `confirmation must be after the tap and match amount and note`() {
        val draft = PaymentDraft(100, "Testing", null)
        assertEquals(22 * 60 + 30, ChatBubbleParser.findConfirmation(bubbles, draft, 22 * 60 + 30)?.minuteOfDay)
        // Same amount and note but earlier: that is an old payment.
        assertNull(ChatBubbleParser.findConfirmation(bubbles, draft, 22 * 60 + 31))
        assertNull(ChatBubbleParser.findConfirmation(bubbles, PaymentDraft(100, "Rent", null), 22 * 60))
        assertNull(ChatBubbleParser.findConfirmation(bubbles, PaymentDraft(200, "Testing", null), 22 * 60))
    }

    @Test fun `parses real Google Pay chat, skipping older days`() {
        val gpay = ChatBubbleParser.parse(fixture("gpay_chat_real").map { it.replace("\\n", "\n") })
        assertEquals(
            listOf(
                ChatBubble(4000, ScreenStatus.SUCCESS, null, 9 * 60 + 40, "SHOP NAME"),
                ChatBubble(100, ScreenStatus.SUCCESS, "milk", 22 * 60 + 26, "SHOP NAME"),
                ChatBubble(100, ScreenStatus.SUCCESS, "milk", 22 * 60 + 43, "SHOP NAME"),
            ),
            gpay,
        )
    }

    @Test fun `failed bubble`() {
        val b = ChatBubbleParser.parse(listOf("₹50", "Failed", "11:02 AM")).single()
        assertEquals(ChatBubble(5000, ScreenStatus.FAILED, null, 11 * 60 + 2), b)
    }

    @Test fun `time parsing`() {
        assertEquals(0, ChatBubbleParser.minuteOfDay("12:00 AM"))
        assertEquals(12 * 60, ChatBubbleParser.minuteOfDay("12:00 PM"))
        assertEquals(22 * 60 + 23, ChatBubbleParser.minuteOfDay("10:23 pm"))
        assertNull(ChatBubbleParser.minuteOfDay("13:00 PM"))
    }
}

class BubbleMatcherTest {
    private val milk = ChatBubble(100, ScreenStatus.SUCCESS, "milk", 22 * 60 + 58, "SHOP")

    @Test fun `two identical payments in the same minute are two entries`() {
        val claimed = mutableSetOf<String>()
        val keyed = BubbleMatcher.keyed(listOf(milk, milk))
        assertEquals(2, BubbleMatcher.unrecorded(keyed, emptyList(), claimed, 22 * 60 + 59, 3).size)
        // Seeing the same chat again adds nothing.
        assertEquals(0, BubbleMatcher.unrecorded(BubbleMatcher.keyed(listOf(milk, milk)), emptyList(), claimed, 22 * 60 + 59, 3).size)
        // A third identical payment appears: exactly one new entry.
        assertEquals(1, BubbleMatcher.unrecorded(BubbleMatcher.keyed(listOf(milk, milk, milk)), emptyList(), claimed, 22 * 60 + 59, 3).size)
    }

    @Test fun `a pay tap accounts for one bubble only`() {
        val tap = RecordedPayment(1, PaymentDraft(100, "milk", null), 22 * 60 + 58, 0)
        val fresh = BubbleMatcher.unrecorded(BubbleMatcher.keyed(listOf(milk, milk)), listOf(tap), mutableSetOf(), 22 * 60 + 59, 3)
        assertEquals(listOf(milk), fresh)
    }

    @Test fun `old bubbles are history`() =
        assertTrue(BubbleMatcher.unrecorded(BubbleMatcher.keyed(listOf(milk)), emptyList(), mutableSetOf(), 23 * 60 + 5, 3).isEmpty())
}

class PaySheetTest {
    @Test fun `real Google Pay pay sheet`() {
        val nodes = listOf("Logo", "Axis Bank", "Close", "Pay ₹1.00", "To SHOP NAME").map { ScreenNode(it) }
        assertEquals(PaymentDraft(100, null, "SHOP NAME"), PaymentFormParser.parsePaySheet(nodes))
    }

    @Test fun `home screen pay buttons are not a sheet`() =
        assertNull(PaymentFormParser.parsePaySheet(listOf("Pay anyone", "Tap & Pay", "Pay").map { ScreenNode(it) }))
}
