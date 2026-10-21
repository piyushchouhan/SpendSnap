package com.spendsnap.parsing

import com.spendsnap.domain.ExtractionMethod
import com.spendsnap.domain.PaymentApp
import com.spendsnap.domain.ReviewIssue
import com.spendsnap.domain.ScreenStatus
import com.spendsnap.domain.Transaction
import com.spendsnap.domain.VerificationStatus

data class Extraction(
    val status: ScreenStatus,
    val description: String?,
    /** Which rule produced the description: "Note" or "Paid to". */
    val descriptionRule: String?,
    val amountPaise: Long?,
    val currency: String?,
    val transactionReference: String?,
    val amountCandidates: List<Long>,
    val issues: Set<ReviewIssue>,
)

/** Combines the deterministic rules. Used for accessibility text, notification text and OCR text alike. */
object PaymentExtractor {

    /** Returns null for sensitive screens (PIN/OTP/password): those are dropped without parsing. */
    fun extract(rawLines: List<String>): Extraction? {
        val lines = rawLines.map { it.replace(Regex("""\s+"""), " ").trim() }.filter { it.isNotEmpty() }
        if (SensitiveContent.isSensitive(lines)) return null

        val issues = mutableSetOf<ReviewIssue>()
        val amount = AmountParser.parse(lines)
        val (paise, currency, candidates) = when (amount) {
            is AmountResult.Found -> {
                if (amount.ocrCorrected) issues += ReviewIssue.AMOUNT_OCR_CORRECTED
                Triple(amount.paise, amount.currency, listOf(amount.paise))
            }
            is AmountResult.Ambiguous -> {
                issues += ReviewIssue.AMOUNT_AMBIGUOUS
                Triple(null, null, amount.candidatesPaise)
            }
            AmountResult.Missing -> {
                issues += ReviewIssue.AMOUNT_MISSING
                Triple(null, null, emptyList())
            }
        }

        val note = DescriptionParser.note(lines)
        val description = note ?: DescriptionParser.payee(lines)
        val descriptionRule = when {
            note != null -> "Note"
            description != null -> "Paid to"
            else -> null
        }
        if (description == null) issues += ReviewIssue.DESCRIPTION_MISSING

        val status = StatusClassifier.classify(lines)
        if (status != ScreenStatus.SUCCESS && status != ScreenStatus.FAILED) issues += ReviewIssue.STATUS_UNVERIFIED

        return Extraction(status, description, descriptionRule, paise, currency, ReferenceParser.find(lines), candidates, issues)
    }
}

fun ScreenStatus.toVerificationStatus(): VerificationStatus = when (this) {
    ScreenStatus.SUCCESS -> VerificationStatus.SUCCESS_REPORTED
    ScreenStatus.PENDING -> VerificationStatus.PENDING
    ScreenStatus.FAILED -> VerificationStatus.FAILED
    else -> VerificationStatus.UNVERIFIED
}

fun Extraction.toTransaction(
    app: PaymentApp,
    method: ExtractionMethod,
    timestamp: Long,
    extraIssues: Set<ReviewIssue> = emptySet(),
    descriptionOverride: String? = null,
): Transaction {
    val finalDescription = description ?: descriptionOverride
    val finalIssues = (issues + extraIssues).let {
        if (finalDescription != null) it - ReviewIssue.DESCRIPTION_MISSING else it
    }
    return Transaction(
        description = finalDescription,
        amountPaise = amountPaise,
        currency = currency ?: "INR",
        paymentApp = app,
        timestamp = timestamp,
        verificationStatus = if (ReviewIssue.NO_PAYMENT_FLOW_SEEN in finalIssues) VerificationStatus.UNVERIFIED else status.toVerificationStatus(),
        extractionMethod = method,
        transactionReference = transactionReference,
        reviewIssues = finalIssues,
    )
}
