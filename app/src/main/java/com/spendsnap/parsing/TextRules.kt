package com.spendsnap.parsing

import com.spendsnap.domain.ScreenStatus

/** Pulls the user's payment note, or the payee as a fallback description. */
object DescriptionParser {
    private val INLINE_LABEL = Regex(
        """^(?:note|message|remark|remarks|description|purpose|comment)\s*[:\-–]\s*(.*)$""",
        RegexOption.IGNORE_CASE,
    )
    // A bare label line ("Note") takes the next line as its value. "Message" is excluded
    // because payment apps also use it as a button label.
    private val BARE_LABEL = Regex("""^(?:note|remark|remarks|description|purpose)$""", RegexOption.IGNORE_CASE)
    private val PLACEHOLDERS = setOf(
        "add a note", "add note", "add a message", "add message", "what's this for?",
        "what is this for?", "optional", "add note (optional)",
    )
    private val PAYEE = Regex(
        """\b(?:paid|sent|payment(?:\s+of)?)(?:\s+(?:₹|rs\.?|inr)\s*[\d,]+(?:\.\d{1,2})?)?\s+to\s*:?\s+(.+)""",
        RegexOption.IGNORE_CASE,
    )
    private val PAYEE_STOP = Regex(
        """\s+(?:is|was|has|successful|successfully|via|using|from|on|at)\b.*$|[.!].*$""",
        RegexOption.IGNORE_CASE,
    )
    private val BARE_PAYEE_LABEL = Regex("""^(?:paid to|payment to|sent to)\s*:?$""", RegexOption.IGNORE_CASE)
    private val LOOKS_LIKE_AMOUNT = Regex("""^(?:₹|rs\.?|inr)?\s*[\d,]+(?:\.\d{1,2})?$""", RegexOption.IGNORE_CASE)

    fun note(lines: List<String>): String? {
        lines.forEachIndexed { i, raw ->
            val line = raw.trim()
            INLINE_LABEL.matchEntire(line)?.let { m -> clean(m.groupValues[1])?.let { return it } }
            if (BARE_LABEL.matches(line)) clean(lines.getOrNull(i + 1))?.let { return it }
        }
        return null
    }

    fun payee(lines: List<String>): String? {
        lines.forEachIndexed { i, line ->
            // PhonePe: "Paid to" on its own line, the name on the next.
            if (BARE_PAYEE_LABEL.matches(line.trim())) {
                clean(lines.getOrNull(i + 1))?.takeIf { n -> n.any { it.isLetter() } }?.let { return it }
            }
        }
        for (line in lines) {
            val name = PAYEE.find(line)?.groupValues?.get(1)?.replace(PAYEE_STOP, "")?.trim() ?: continue
            if (name.isNotEmpty() && name.length <= 60 && name.any { it.isLetter() }) return name
        }
        return null
    }

    private fun clean(value: String?): String? {
        val v = value?.trim()?.trim('"', '\'')?.trim() ?: return null
        if (v.isEmpty() || v.length > 100) return null
        if (v.lowercase() in PLACEHOLDERS) return null
        if (LOOKS_LIKE_AMOUNT.matches(v)) return null
        return v
    }
}

enum class LineTag(val label: String) { AMOUNT("Amount"), DESCRIPTION("Description"), ANCHOR("Rule anchor") }

/** Marks which recognized lines the rules used, so the review screen can show its work. */
object LineTagger {
    private val ANCHOR = Regex("""^(?:paid to|payment to|sent to|note|message|remarks?|description|purpose)\s*:?$""", RegexOption.IGNORE_CASE)

    fun tag(lines: List<String>, amountPaise: Long?, description: String?): List<Pair<String, LineTag?>> {
        var amountDone = false
        var descriptionDone = false
        return lines.map { line ->
            val t = line.trim()
            val tag = when {
                !amountDone && amountPaise != null && AmountParser.candidates(listOf(t)).any { it.paise == amountPaise } ->
                    LineTag.AMOUNT.also { amountDone = true }
                !descriptionDone && description != null && t.contains(description, ignoreCase = true) ->
                    LineTag.DESCRIPTION.also { descriptionDone = true }
                ANCHOR.matches(t) -> LineTag.ANCHOR
                else -> null
            }
            t to tag
        }
    }
}

data class Incoming(val amountPaise: Long, val from: String?)

/** Money received, from a payment-app notification such as "Rahul has sent you ₹200". */
object IncomingParser {
    private val INCOMING = Regex("""\b(?:received|credited|sent you|has sent you)\b""", RegexOption.IGNORE_CASE)
    // Promotions and requests mention money without any arriving.
    private val NOT_MONEY_IN = Regex(
        """\b(?:cashback|reward|rewards|scratch|coupon|offer|win|won|request|requested|requesting|collect|due|reminder)\b""",
        RegexOption.IGNORE_CASE,
    )
    private val SENT_YOU = Regex("""^(.+?)\s+(?:has\s+)?sent you\b""", RegexOption.IGNORE_CASE)
    private val FROM = Regex("""\bfrom\s+(.+?)(?:\s+(?:on|via|in|to|is|was|using)\b|[.!,]|$)""", RegexOption.IGNORE_CASE)

    fun parse(lines: List<String>): Incoming? {
        val text = lines.joinToString("\n")
        if (!INCOMING.containsMatchIn(text) || NOT_MONEY_IN.containsMatchIn(text)) return null
        val amount = (AmountParser.parse(lines) as? AmountResult.Found)?.paise ?: return null
        val from = (lines.firstNotNullOfOrNull { SENT_YOU.find(it.trim())?.groupValues?.get(1) }
            ?: lines.firstNotNullOfOrNull { FROM.find(it)?.groupValues?.get(1) })
            ?.trim()?.takeIf { n -> n.length in 2..60 && n.any { it.isLetter() } && !n.contains('₹') }
        return Incoming(amount, from)
    }
}

object StatusClassifier {
    private fun words(vararg phrases: String) =
        Regex(phrases.joinToString("|", """\b(?:""", """)\b""") { Regex.escape(it) }, RegexOption.IGNORE_CASE)

    // Incoming money and payment requests are not expenses.
    private val NOT_EXPENSE = words(
        "received", "credited", "sent you", "has sent you", "requested", "is requesting", "payment request", "collect request",
    )
    private val FAILED = words(
        "payment failed", "transaction failed", "failed", "declined", "unsuccessful", "not successful",
        "could not be completed", "couldn't be completed", "not completed", "cancelled", "canceled", "will be refunded", "refunded",
    )
    private val PENDING = words("pending", "processing", "in progress", "awaiting", "under process", "will be updated")
    private val STRONG_SUCCESS = words(
        "payment successful", "paid successfully", "transaction successful", "successful", "successfully",
        "money sent", "sent successfully", "payment completed", "completed",
    )
    // "Paid to X" / "debited" also appear on failed and pending screens, so they never override those.
    private val WEAK_SUCCESS = words("paid", "debited")
    // Bare "pay" is not enough: the GPay home screen shows "Pay anyone" and "Tap & Pay" (seen in a real dump).
    private val AMOUNT_ENTRY = words("enter amount", "add a note", "add note", "proceed to pay", "pay now", "proceed")

    fun classify(lines: List<String>): ScreenStatus {
        val text = lines.joinToString("\n")
        if (NOT_EXPENSE.containsMatchIn(text)) return ScreenStatus.NOT_AN_EXPENSE
        val failed = FAILED.containsMatchIn(text)
        // Remove failure phrases first so "not successful" does not also count as success.
        val withoutFailures = FAILED.replace(text, " ")
        val strongSuccess = STRONG_SUCCESS.containsMatchIn(withoutFailures)
        val weakSuccess = WEAK_SUCCESS.containsMatchIn(withoutFailures)
        val pending = PENDING.containsMatchIn(text)
        return when {
            failed && strongSuccess -> ScreenStatus.UNKNOWN
            failed -> ScreenStatus.FAILED
            pending -> ScreenStatus.PENDING
            strongSuccess || weakSuccess -> ScreenStatus.SUCCESS
            AMOUNT_ENTRY.containsMatchIn(text) -> ScreenStatus.AMOUNT_ENTRY
            else -> ScreenStatus.UNKNOWN
        }
    }
}

object ReferenceParser {
    private val LABELED = Regex(
        """(?:upi\s*(?:transaction|txn|ref(?:erence)?)\s*(?:id|no\.?|number)?|utr(?:\s*(?:no\.?|number))?|transaction\s*id|txn\s*id|ref(?:erence)?\s*(?:no\.?|number|id))\s*[:#\-]?\s*([A-Za-z0-9]{10,35})\b""",
        RegexOption.IGNORE_CASE,
    )

    /** Only returns a reference that is clearly labeled; it is used as the strongest duplicate key. */
    fun find(lines: List<String>): String? =
        LABELED.findAll(lines.joinToString("\n"))
            .map { it.groupValues[1].uppercase() }
            .firstOrNull { ref -> ref.count { it.isDigit() } >= 6 }
}

/** Screens that must never be read or stored. */
object SensitiveContent {
    private val MARKERS = Regex(
        """\b(?:upi pin|enter pin|mpin|otp|one time password|password|passcode|cvv)\b""",
        RegexOption.IGNORE_CASE,
    )

    fun isSensitive(lines: List<String>): Boolean = lines.any { MARKERS.containsMatchIn(it) }
}
