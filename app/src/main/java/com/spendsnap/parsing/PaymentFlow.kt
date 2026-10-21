package com.spendsnap.parsing

import com.spendsnap.domain.Inr
import com.spendsnap.domain.ScreenStatus

/** One visible accessibility node, reduced to what the rules need. */
data class ScreenNode(
    val text: String,
    val editable: Boolean = false,
    val clickable: Boolean = false,
    val viewId: String? = null,
)

/** What the user typed on the amount-entry screen. This is the source of truth for amount and note. */
data class PaymentDraft(val amountPaise: Long, val note: String?, val payee: String?)

object PaymentFormParser {
    private val BANKING_NAME = Regex("""banking name\s*:\s*(.+)""", RegexOption.IGNORE_CASE)
    private val PAY_BUTTONS = setOf("pay", "pay now", "proceed to pay", "send", "send money", "proceed")

    /**
     * Reads only editable fields: the amount field (a number) and the note field (anything else).
     * Chat bubbles above the form are plain text, so they are never mistaken for the new payment.
     */
    fun parse(nodes: List<ScreenNode>): PaymentDraft? {
        val fields = nodes.filter { it.editable && it.text.isNotBlank() }
        val amount = fields.firstNotNullOfOrNull { Inr.parseUserInput(it.text) } ?: return null
        val note = fields.firstOrNull { Inr.parseUserInput(it.text) == null }?.text?.trim()?.takeIf { it.length <= 100 }
        val payee = nodes.firstNotNullOfOrNull { BANKING_NAME.find(it.text)?.groupValues?.get(1)?.trim() }
        return PaymentDraft(amount, note, payee)
    }

    private val SHEET_AMOUNT = Regex("""^Pay\s*₹\s*([\d,]+(?:\.\d{1,2})?)$""", RegexOption.IGNORE_CASE)
    private val SHEET_PAYEE = Regex("""^To\s+(.+)$""", RegexOption.IGNORE_CASE)

    /**
     * Google Pay's last step before the UPI PIN: a sheet headed "Pay ₹1.00" / "To SHOP". Its
     * amount-entry screen is not readable, so this sheet is where a payment is first seen.
     */
    fun parsePaySheet(nodes: List<ScreenNode>): PaymentDraft? {
        val texts = nodes.map { it.text.trim() }
        texts.forEachIndexed { i, t ->
            val amount = SHEET_AMOUNT.matchEntire(t)?.let { Inr.parseUserInput(it.groupValues[1]) } ?: return@forEachIndexed
            val payee = texts.getOrNull(i + 1)?.let { SHEET_PAYEE.matchEntire(it)?.groupValues?.get(1)?.trim() }
            return PaymentDraft(amount, note = null, payee = payee)
        }
        return null
    }

    fun isPayButton(text: String): Boolean {
        val t = text.trim().lowercase()
        return t in PAY_BUTTONS || t.startsWith("pay ₹")
    }
}

/**
 * A payment bubble in a chat-style history. Two real layouts:
 * PhonePe: "₹1" / "PAID" / "Testing" / "10:30 PM" (separate nodes)
 * Google Pay: "Payment to SHOP⏎milk⏎₹1⏎Paid • 10:43 pm" (one node)
 */
data class ChatBubble(
    val amountPaise: Long,
    val status: ScreenStatus,
    val note: String?,
    val minuteOfDay: Int,
    val payee: String? = null,
)

object ChatBubbleParser {
    private val AMOUNT = Regex("""^₹\s*([\d,]+(?:\.\d{1,2})?)$""")
    private val TIME = Regex("""^(\d{1,2}):(\d{2})\s*([AaPp])\.?\s*[Mm]\.?$""")
    private val STATUS_DOT = Regex("""^(\w+)\s*•\s*(.+)$""")
    private val PAYEE = Regex("""^(?:payment to|paid to)\s+(.+)$""", RegexOption.IGNORE_CASE)
    private val STATUS = mapOf(
        "paid" to ScreenStatus.SUCCESS,
        "sent" to ScreenStatus.SUCCESS,
        "successful" to ScreenStatus.SUCCESS,
        "failed" to ScreenStatus.FAILED,
        "declined" to ScreenStatus.FAILED,
        "pending" to ScreenStatus.PENDING,
        "processing" to ScreenStatus.PENDING,
    )

    fun parse(lines: List<String>): List<ChatBubble> {
        val l = lines.flatMap { it.split('\n') }.map { it.trim() }
        val out = mutableListOf<ChatBubble>()
        var i = 0
        while (i < l.size - 1) {
            val paise = AMOUNT.matchEntire(l[i])?.let { Inr.parseUserInput(it.groupValues[1]) }
            if (paise == null) {
                i++
                continue
            }
            val dotted = STATUS_DOT.matchEntire(l[i + 1])
            if (dotted != null) {
                // Google Pay: payee and optional note come before the amount. A date instead of a
                // time ("Paid • 19 Oct") means an older day, so the bubble is skipped.
                val status = STATUS[dotted.groupValues[1].lowercase()]
                val minute = minuteOfDay(dotted.groupValues[2])
                val before = l.getOrNull(i - 1)
                val payeeLine = listOfNotNull(before, l.getOrNull(i - 2)).firstNotNullOfOrNull { PAYEE.matchEntire(it) }
                val note = before?.takeIf { PAYEE.matchEntire(it) == null && payeeLine != null && it.isNotEmpty() }
                if (status != null && minute != null) {
                    out += ChatBubble(paise, status, note, minute, payeeLine?.groupValues?.get(1)?.trim())
                }
                i += 2
                continue
            }
            val status = STATUS[l[i + 1].lowercase()]
            if (status == null || i + 2 >= l.size) {
                i++
                continue
            }
            // PhonePe: optional note, then a time.
            val noteCandidate = l[i + 2]
            val (note, timeLine, next) = if (TIME.matches(noteCandidate)) {
                Triple(null, noteCandidate, i + 3)
            } else {
                Triple(noteCandidate.ifEmpty { null }, l.getOrNull(i + 3), i + 4)
            }
            val minute = timeLine?.let(::minuteOfDay)
            if (minute != null) out += ChatBubble(paise, status, note, minute)
            i = if (minute != null) next else i + 1
        }
        return out
    }

    fun minuteOfDay(text: String): Int? {
        val m = TIME.matchEntire(text.trim()) ?: return null
        val hour = m.groupValues[1].toInt()
        val minute = m.groupValues[2].toInt()
        if (hour !in 1..12 || minute > 59) return null
        val pm = m.groupValues[3].equals("p", ignoreCase = true)
        return ((hour % 12) + if (pm) 12 else 0) * 60 + minute
    }

    fun confirms(b: ChatBubble, draft: PaymentDraft, submittedMinute: Int): Boolean =
        b.amountPaise == draft.amountPaise &&
            b.minuteOfDay >= submittedMinute &&
            (draft.note == null || b.note == null || b.note.equals(draft.note, ignoreCase = true))

    /** The bubble that confirms a payment submitted at [submittedMinute] today, if any. */
    fun findConfirmation(bubbles: List<ChatBubble>, draft: PaymentDraft, submittedMinute: Int): ChatBubble? =
        bubbles.lastOrNull { confirms(it, draft, submittedMinute) }
}

/** A payment this app already recorded. A chat bubble can account for it at most once. */
class RecordedPayment(val id: Long, val draft: PaymentDraft, val minuteOfDay: Int, val at: Long) {
    var bubbleKey: String? = null
}

/** One bubble = one payment, even when amount, note and minute are identical. */
object BubbleMatcher {
    /** Identical bubbles get #1, #2… in screen order, so two equal payments stay two. */
    fun keyed(bubbles: List<ChatBubble>): List<Pair<String, ChatBubble>> {
        val counts = mutableMapOf<String, Int>()
        return bubbles.map { b ->
            val base = "${b.payee}|${b.amountPaise}|${b.note}|${b.minuteOfDay}"
            val n = (counts[base] ?: 0) + 1
            counts[base] = n
            "$base#$n" to b
        }
    }

    /**
     * Returns fresh "Paid" bubbles that no recorded payment accounts for. Bubbles that match an
     * unclaimed recorded payment are linked to it instead. [claimed] is updated in place.
     */
    fun unrecorded(
        keyed: List<Pair<String, ChatBubble>>,
        recorded: List<RecordedPayment>,
        claimed: MutableSet<String>,
        nowMinute: Int,
        freshMinutes: Int,
    ): List<ChatBubble> = keyed.mapNotNull { (key, b) ->
        if (b.status != ScreenStatus.SUCCESS || nowMinute - b.minuteOfDay !in 0..freshMinutes || key in claimed) {
            return@mapNotNull null
        }
        claimed += key
        val owner = recorded.firstOrNull {
            it.bubbleKey == null &&
                it.draft.amountPaise == b.amountPaise &&
                b.minuteOfDay - it.minuteOfDay in -1..freshMinutes &&
                (it.draft.note == null || b.note == null || it.draft.note.equals(b.note, ignoreCase = true))
        }
        if (owner != null) {
            owner.bubbleKey = key
            null
        } else {
            b
        }
    }
}
