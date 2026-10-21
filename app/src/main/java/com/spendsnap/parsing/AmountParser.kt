package com.spendsnap.parsing

import com.spendsnap.domain.Inr

data class AmountCandidate(
    val paise: Long,
    val lineIndex: Int,
    val hasCurrency: Boolean,
    val labeled: Boolean,
    val ocrCorrected: Boolean,
)

sealed interface AmountResult {
    data class Found(val paise: Long, val currency: String?, val ocrCorrected: Boolean) : AmountResult
    data class Ambiguous(val candidatesPaise: List<Long>) : AmountResult
    data object Missing : AmountResult
}

/**
 * Deterministic amount extraction. It never guesses: when several different amounts
 * remain after the rules below, the result is [AmountResult.Ambiguous] and goes to review.
 */
object AmountParser {
    // Number token: letters O/o/I/l are allowed because OCR often confuses them with 0/1.
    private const val NUMBER = """([0-9OoIl][0-9OoIl,]*(?:\.[0-9OoIl]{1,2})?)(?![0-9A-Za-z])"""
    private val WITH_CURRENCY = Regex("""(?:₹|\bRs\.?|\bINR)\s*$NUMBER""", RegexOption.IGNORE_CASE)
    private val LABELED_PLAIN = Regex("""\b(?:amount|total)\b\s*[:\-]?\s*$NUMBER""", RegexOption.IGNORE_CASE)
    private val LABEL_ONLY = Regex("""^(?:amount|total|amount paid|total amount|you paid)\s*:?$""", RegexOption.IGNORE_CASE)
    private val LABEL_WORDS = Regex("""\b(?:amount|total|paid|sent|debited|pay)\b""", RegexOption.IGNORE_CASE)

    /** Lines mentioning these are promotions, balances or limits, not the payment amount. */
    private val EXCLUDED = Regex(
        """\b(?:balance|cashback|reward|rewards|limit|offer|offers|upto|up to|win|won|earn|scratch|coupon|voucher|minimum|min\.?)\b""",
        RegexOption.IGNORE_CASE,
    )

    private val PLAIN_DIGITS = Regex("""^\d+$""")
    private val WESTERN = Regex("""^\d{1,3}(?:,\d{3})+$""")
    private val INDIAN = Regex("""^\d{1,2}(?:,\d{2})*,\d{3}$""")

    fun candidates(lines: List<String>): List<AmountCandidate> {
        val out = mutableListOf<AmountCandidate>()
        lines.forEachIndexed { index, line ->
            if (EXCLUDED.containsMatchIn(line)) return@forEachIndexed
            val previousIsLabel = index > 0 && LABEL_ONLY.matches(lines[index - 1].trim())
            val labeledLine = previousIsLabel || LABEL_WORDS.containsMatchIn(line)
            WITH_CURRENCY.findAll(line).forEach { m ->
                toCandidate(m.groupValues[1], index, hasCurrency = true, labeled = labeledLine)?.let(out::add)
            }
            if (out.none { it.lineIndex == index }) {
                LABELED_PLAIN.findAll(line).forEach { m ->
                    toCandidate(m.groupValues[1], index, hasCurrency = false, labeled = true)?.let(out::add)
                }
            }
        }
        return out
    }

    fun parse(lines: List<String>): AmountResult {
        val all = candidates(lines)
        if (all.isEmpty()) return AmountResult.Missing
        val chosen = pick(all) ?: return AmountResult.Ambiguous(all.map { it.paise }.distinct())
        val matching = all.filter { it.paise == chosen }
        return AmountResult.Found(
            paise = chosen,
            currency = if (matching.any { it.hasCurrency }) "INR" else null,
            ocrCorrected = matching.all { it.ocrCorrected },
        )
    }

    private fun pick(all: List<AmountCandidate>): Long? {
        all.map { it.paise }.distinct().singleOrNull()?.let { return it }
        return all.filter { it.labeled }.map { it.paise }.distinct().singleOrNull()
    }

    private fun toCandidate(raw: String, index: Int, hasCurrency: Boolean, labeled: Boolean): AmountCandidate? {
        if (raw.none { it.isDigit() }) return null
        val fixed = raw.map {
            when (it) {
                'O', 'o' -> '0'
                'I', 'l' -> '1'
                else -> it
            }
        }.joinToString("")
        val trimmed = fixed.trimEnd(',')
        val parts = trimmed.split('.')
        val integerPart = parts[0]
        if (!(PLAIN_DIGITS.matches(integerPart) || WESTERN.matches(integerPart) || INDIAN.matches(integerPart))) return null
        val paise = Inr.toPaise(integerPart.replace(",", ""), parts.getOrNull(1)) ?: return null
        return AmountCandidate(paise, index, hasCurrency, labeled, ocrCorrected = fixed != raw)
    }
}
