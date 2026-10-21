package com.spendsnap.domain

/** Exact INR handling on integer paise. No floating point anywhere. */
object Inr {
    /** ₹10 crore: anything larger is treated as a misread, not a payment. */
    const val MAX_PAISE = 10_00_00_000_00L

    private val USER_INPUT = Regex("""^(\d{1,10})(?:\.(\d{1,2}))?$""")

    /** "₹1,23,456" or "₹1,23,456.50" (paise shown only when non-zero). */
    fun format(paise: Long): String {
        require(paise != Long.MIN_VALUE)
        val abs = if (paise < 0) -paise else paise
        val fraction = abs % 100
        val text = "₹" + groupIndian((abs / 100).toString()) +
            if (fraction != 0L) "." + fraction.toString().padStart(2, '0') else ""
        return if (paise < 0) "-$text" else text
    }

    /** Plain rupees with exactly two decimals, e.g. "1234.50". Used for CSV. */
    fun plain(paise: Long): String {
        require(paise >= 0)
        return "${paise / 100}.${(paise % 100).toString().padStart(2, '0')}"
    }

    /** Indian digit grouping: 1234567 -> 12,34,567. */
    fun groupIndian(digits: String): String {
        if (digits.length <= 3) return digits
        val groups = ArrayDeque<String>()
        var rest = digits.dropLast(3)
        while (rest.length > 2) {
            groups.addFirst(rest.takeLast(2))
            rest = rest.dropLast(2)
        }
        if (rest.isNotEmpty()) groups.addFirst(rest)
        return groups.joinToString(",") + "," + digits.takeLast(3)
    }

    /** Converts already-validated digit strings to paise; null on overflow or out of range. */
    fun toPaise(rupeeDigits: String, fractionDigits: String?): Long? {
        val rupees = rupeeDigits.toLongOrNull() ?: return null
        val fraction = (fractionDigits ?: "").padEnd(2, '0').take(2).toLong()
        val paise = try {
            Math.addExact(Math.multiplyExact(rupees, 100L), fraction)
        } catch (_: ArithmeticException) {
            return null
        }
        return paise.takeIf { it in 1..MAX_PAISE }
    }

    /** Parses what the user typed in the editor ("1,234.5", "₹ 99"). */
    fun parseUserInput(text: String): Long? {
        val cleaned = text.replace("₹", "").replace(",", "").trim()
        val match = USER_INPUT.matchEntire(cleaned) ?: return null
        return toPaise(match.groupValues[1], match.groupValues[2].ifEmpty { null })
    }

    /** Editor representation: "1234.5" -> "1234.50", whole rupees without decimals. */
    fun toEditable(paise: Long): String =
        if (paise % 100 == 0L) (paise / 100).toString() else plain(paise)
}
